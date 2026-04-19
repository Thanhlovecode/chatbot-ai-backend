package dev.thanh.spring_ai.utils;

import dev.thanh.spring_ai.exception.ServiceDegradedException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Centralized Redis CircuitBreaker executor with operation priority classification.
 * <p>
 * Phân loại 4 tầng execution strategy:
 * <ol>
 *   <li><b>CRITICAL</b> — {@link #executeCriticalWithFallback}, {@link #tryCriticalExecuteOrElse}:
 *       Redis fail → fallback qua DB Bulkhead (bảo vệ HikariCP pool)</li>
 *   <li><b>NON-CRITICAL</b> — {@link #executeOrReject}:
 *       CB OPEN → throw {@link ServiceDegradedException} (HTTP 503), transient fail → safe default</li>
 *   <li><b>FIRE-AND-FORGET</b> — {@link #tryExecute}:
 *       Redis fail → skip silently (nice-to-have operations)</li>
 *   <li><b>FAIL-OPEN</b> — {@link #executeWithFallback}:
 *       Redis fail → return safe default, cho request đi tiếp (e.g., rate limiting)</li>
 * </ol>
 * <p>
 * Tất cả Redis operations trong project đi qua class này để đảm bảo:
 * <ol>
 * <li>Chung 1 CircuitBreaker instance → metrics thống nhất, detect failure
 * chính xác</li>
 * <li>Tự động increment Prometheus counter khi fallback/skip xảy ra</li>
 * <li>Đo latency per-operation với histogram → hỗ trợ p95/p99 trên Grafana</li>
 * <li>DB Bulkhead bảo vệ connection pool khi Redis failure gây fallback spike</li>
 * <li>Loại bỏ boilerplate try-catch lặp lại ở mọi service</li>
 * </ol>
 */
@Component
@Slf4j
public class SafeRedisExecutor {

    private static final String CB_NAME = "redis";
    private static final String DB_BULKHEAD_NAME = "db-fallback";
    private static final String TIMER_NAME = "redis_operation_duration_seconds";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FALLBACK = "fallback";
    private static final String COUNTER_FALLBACK = "redis.fallback";

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead dbFallbackBulkhead;
    private final MeterRegistry meterRegistry;

    /**
     * Cache Timer instances theo key "op:outcome" để tránh tạo mới mỗi request.
     * Mỗi tổ hợp (op, outcome) chỉ register Timer 1 lần duy nhất.
     */
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public SafeRedisExecutor(CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            MeterRegistry meterRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME);
        this.dbFallbackBulkhead = bulkheadRegistry.bulkhead(DB_BULKHEAD_NAME);
        this.meterRegistry = meterRegistry;
        log.info("SafeRedisExecutor initialized — CB: '{}' ({}), DB Bulkhead: '{}' (max={})",
                CB_NAME, circuitBreaker.getState(),
                DB_BULKHEAD_NAME, dbFallbackBulkhead.getBulkheadConfig().getMaxConcurrentCalls());
    }

    /**
     * Lấy hoặc tạo Timer (cached) cho tổ hợp (op, outcome).
     * Dùng {@code publishPercentileHistogram()} để Prometheus scrape histogram
     * buckets,
     * cho phép tính p95/p99 chính xác bằng {@code histogram_quantile()} trên
     * Grafana.
     */
    private Timer getOrCreateTimer(String op, String outcome) {
        String key = op + ":" + outcome;
        return timerCache.computeIfAbsent(key, k -> Timer.builder(TIMER_NAME)
                .tag("op", op)
                .tag("outcome", outcome)
                .description("Duration of individual Redis operations")
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FAIL-OPEN: Redis fail → return safe default, request tiếp tục
    // Dùng cho: RateLimitService (fail-open khi Redis down)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Chạy Redis operation, nếu fail → chạy fallback.
     * <p>
     * Dùng cho operations có return value cần đảm bảo kết quả
     * (e.g., checkTokenBucket → fallback to null → cho request đi tiếp).
     * <p>
     * Tự động đo latency và ghi vào histogram metric
     * {@code redis_operation_duration_seconds}.
     *
     * @param redisOp      Redis operation cần thực thi
     * @param fallback     giá trị trả về khi Redis fail hoặc CB OPEN
     * @param operationTag tên operation cho logging và Prometheus metrics
     * @param <T>          kiểu return value
     * @return kết quả từ Redis hoặc fallback
     */
    public <T> T executeWithFallback(Supplier<T> redisOp, Supplier<T> fallback, String operationTag) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, redisOp);
        try {
            T result = decorated.get();
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_SUCCESS));
            return result;
        } catch (Exception ex) {
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_FALLBACK));
            meterRegistry.counter(COUNTER_FALLBACK, "op", operationTag).increment();
            log.warn("[Redis][{}] Falling back. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
            return fallback.get();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FIRE-AND-FORGET: Redis fail → skip, luồng chính không bị ảnh hưởng
    // Dùng cho: cacheHistory, trimStream, touchSession, warmUpFromDb...
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Chạy Redis operation, nếu fail → skip hoàn toàn, không fallback.
     * <p>
     * Dùng cho fire-and-forget operations là "nice-to-have"
     * (e.g., cacheHistory, trimStream, touchSession).
     * Luồng chính tiếp tục bình thường khi Redis down.
     * <p>
     * Tự động đo latency và ghi vào histogram metric
     * {@code redis_operation_duration_seconds}.
     *
     * @param redisOp      Redis operation cần thực thi
     * @param operationTag tên operation cho logging và Prometheus metrics
     */
    public void tryExecute(Runnable redisOp, String operationTag) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Runnable decorated = CircuitBreaker.decorateRunnable(circuitBreaker, redisOp);
        try {
            decorated.run();
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_SUCCESS));
        } catch (Exception ex) {
            sample.stop(getOrCreateTimer(operationTag, "skip"));
            meterRegistry.counter("redis.skip", "op", operationTag).increment();
            log.warn("[Redis][{}] Skipped. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CRITICAL: Redis fail → fallback qua DB Bulkhead (bảo vệ connection pool)
    // Dùng cho: pushToStream (direct DB INSERT), chat pipeline operations
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Chạy Redis operation, nếu fail → chạy DB fallback <b>qua Bulkhead</b>.
     * <p>
     * Dùng cho operations CRITICAL trong chat pipeline mà fallback cần truy cập DB.
     * Bulkhead giới hạn concurrent DB fallback calls = HikariCP max pool size,
     * ngăn chặn spike khi Redis failure gây hàng nghìn requests đổ vào DB cùng lúc.
     * <p>
     * Nếu Bulkhead full → throw {@link ServiceDegradedException} (HTTP 503).
     *
     * @param redisOp      Redis operation cần thực thi
     * @param dbFallback   DB fallback operation khi Redis fail
     * @param operationTag tên operation cho logging và Prometheus metrics
     * @param <T>          kiểu return value
     * @return kết quả từ Redis hoặc DB fallback
     * @throws ServiceDegradedException nếu DB Bulkhead đã full
     */
    public <T> T executeCriticalWithFallback(Supplier<T> redisOp, Supplier<T> dbFallback, String operationTag) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, redisOp);
        try {
            T result = decorated.get();
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_SUCCESS));
            return result;
        } catch (Exception ex) {
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_FALLBACK));
            meterRegistry.counter(COUNTER_FALLBACK, "op", operationTag).increment();
            log.warn("[Redis][{}] Critical fallback to DB. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
            return executeDbFallback(dbFallback, operationTag);
        }
    }

    /**
     * Chạy Redis operation, nếu fail → chạy DB fallback <b>qua Bulkhead</b> (void version).
     * <p>
     * Dùng cho fire-and-forget operations CRITICAL mà fallback cần truy cập DB
     * (e.g., pushToStream fail → direct DB INSERT fallback).
     *
     * @param redisOp      Redis operation cần thực thi
     * @param dbFallback   DB fallback action khi Redis fail
     * @param operationTag tên operation cho logging và Prometheus metrics
     * @throws ServiceDegradedException nếu DB Bulkhead đã full
     */
    public void tryCriticalExecuteOrElse(Runnable redisOp, Runnable dbFallback, String operationTag) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Runnable decorated = CircuitBreaker.decorateRunnable(circuitBreaker, redisOp);
        try {
            decorated.run();
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_SUCCESS));
        } catch (Exception ex) {
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_FALLBACK));
            meterRegistry.counter(COUNTER_FALLBACK, "op", operationTag).increment();
            log.warn("[Redis][{}] Critical fallback to DB. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
            executeDbFallbackRunnable(dbFallback, operationTag);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NON-CRITICAL: CB OPEN → reject (503), transient fail → safe default
    // Dùng cho: session list, timestamps, ZSET queries
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Chạy Redis operation với hành vi phân biệt theo CB state:
     * <ul>
     *   <li><b>CB CLOSED + success</b> → return kết quả bình thường</li>
     *   <li><b>CB CLOSED + transient failure</b> → return safeDefault (giống {@link #executeWithFallback})</li>
     *   <li><b>CB OPEN</b> → throw {@link ServiceDegradedException} (HTTP 503)</li>
     * </ul>
     * <p>
     * Dùng cho non-critical operations (xem danh sách session, lấy timestamps...)
     * mà KHÔNG nên fallback xuống DB khi Redis thực sự chết (CB OPEN), chỉ chấp nhận
     * safe default cho lỗi cá thể nhất thời.
     *
     * @param redisOp      Redis operation cần thực thi
     * @param safeDefault  giá trị an toàn khi Redis lỗi nhất thời (CB vẫn CLOSED)
     * @param operationTag tên operation cho logging và Prometheus metrics
     * @param <T>          kiểu return value
     * @return kết quả từ Redis hoặc safeDefault (chỉ khi transient failure)
     * @throws ServiceDegradedException khi CB OPEN (Redis confirmed dead)
     */
    public <T> T executeOrReject(Supplier<T> redisOp, Supplier<T> safeDefault, String operationTag) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, redisOp);
        try {
            T result = decorated.get();
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_SUCCESS));
            return result;
        } catch (CallNotPermittedException ex) {
            // CB is OPEN → Redis confirmed failing → REJECT non-critical operation
            sample.stop(getOrCreateTimer(operationTag, "rejected"));
            meterRegistry.counter("redis.rejected", "op", operationTag).increment();
            log.warn("[Redis][{}] CB OPEN — non-critical operation rejected", operationTag);
            throw new ServiceDegradedException(
                    "Hệ thống đang quá tải, vui lòng thử lại sau.", ex);
        } catch (Exception ex) {
            // Transient failure (CB still CLOSED/HALF_OPEN) → return safe default
            sample.stop(getOrCreateTimer(operationTag, OUTCOME_FALLBACK));
            meterRegistry.counter(COUNTER_FALLBACK, "op", operationTag).increment();
            log.warn("[Redis][{}] Transient failure, safe default returned. CB state={}",
                    operationTag, circuitBreaker.getState());
            return safeDefault.get();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DB Bulkhead helpers
    // ═══════════════════════════════════════════════════════════════════════

    private <T> T executeDbFallback(Supplier<T> fallback, String operationTag) {
        try {
            T result = dbFallbackBulkhead.executeSupplier(fallback);
            meterRegistry.counter("db.fallback.acquired", "op", operationTag).increment();
            return result;
        } catch (BulkheadFullException ex) {
            meterRegistry.counter("db.fallback.rejected", "op", operationTag).increment();
            log.error("[DB-Bulkhead][{}] Full! max={}, rejecting fallback request",
                    operationTag, dbFallbackBulkhead.getBulkheadConfig().getMaxConcurrentCalls());
            throw new ServiceDegradedException(
                    "Hệ thống đang xử lý quá nhiều yêu cầu, vui lòng thử lại sau.", ex);
        }
    }

    private void executeDbFallbackRunnable(Runnable fallback, String operationTag) {
        try {
            dbFallbackBulkhead.executeRunnable(fallback);
            meterRegistry.counter("db.fallback.acquired", "op", operationTag).increment();
        } catch (BulkheadFullException ex) {
            meterRegistry.counter("db.fallback.rejected", "op", operationTag).increment();
            log.error("[DB-Bulkhead][{}] Full! max={}, rejecting fallback request",
                    operationTag, dbFallbackBulkhead.getBulkheadConfig().getMaxConcurrentCalls());
            throw new ServiceDegradedException(
                    "Hệ thống đang xử lý quá nhiều yêu cầu, vui lòng thử lại sau.", ex);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // State accessors
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lấy trạng thái hiện tại của CircuitBreaker.
     * Dùng cho health monitoring và quyết định gửi SYSTEM_WARNING cho user.
     */
    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }
}
