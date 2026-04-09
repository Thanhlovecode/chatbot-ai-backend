package dev.thanh.spring_ai.utils;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Centralized Redis CircuitBreaker executor.
 * <p>
 * Cung cấp 2 method chính để bọc Redis operations:
 * <ul>
 *   <li>{@link #executeWithFallback} — operations có return value, cần fallback khi Redis fail</li>
 *   <li>{@link #tryExecute} — fire-and-forget operations, skip khi Redis fail</li>
 * </ul>
 *
 * <p>Tất cả Redis operations trong project đi qua class này để đảm bảo:
 * <ol>
 *   <li>Chung 1 CircuitBreaker instance → metrics thống nhất, detect failure chính xác</li>
 *   <li>Tự động increment Prometheus counter khi fallback/skip xảy ra</li>
 *   <li>Loại bỏ boilerplate try-catch lặp lại ở mọi service</li>
 * </ol>
 */
@Component
@Slf4j
public class SafeRedisExecutor {

    private static final String CB_NAME = "redis";

    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;

    public SafeRedisExecutor(CircuitBreakerRegistry circuitBreakerRegistry,
                             MeterRegistry meterRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME);
        this.meterRegistry = meterRegistry;
        log.info("SafeRedisExecutor initialized with CircuitBreaker '{}'. State: {}",
                CB_NAME, circuitBreaker.getState());
    }

    /**
     * Chạy Redis operation, nếu fail → chạy fallback.
     * <p>
     * Dùng cho operations có return value cần đảm bảo kết quả
     * (e.g., getHistory → fallback to empty list → caller query DB).
     *
     * @param redisOp      Redis operation cần thực thi
     * @param fallback     giá trị trả về khi Redis fail hoặc CB OPEN
     * @param operationTag tên operation cho logging và Prometheus metrics
     * @param <T>          kiểu return value
     * @return kết quả từ Redis hoặc fallback
     */
    public <T> T executeWithFallback(Supplier<T> redisOp, Supplier<T> fallback, String operationTag) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, redisOp);
        try {
            return decorated.get();
        } catch (Exception ex) {
            meterRegistry.counter("redis.fallback", "op", operationTag).increment();
            log.warn("[Redis][{}] Falling back. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
            return fallback.get();
        }
    }

    /**
     * Chạy Redis operation, nếu fail → skip hoàn toàn, không fallback.
     * <p>
     * Dùng cho fire-and-forget operations là "nice-to-have"
     * (e.g., cacheHistory, trimStream, touchSession).
     * Luồng chính tiếp tục bình thường khi Redis down.
     *
     * @param redisOp      Redis operation cần thực thi
     * @param operationTag tên operation cho logging và Prometheus metrics
     */
    public void tryExecute(Runnable redisOp, String operationTag) {
        Runnable decorated = CircuitBreaker.decorateRunnable(circuitBreaker, redisOp);
        try {
            decorated.run();
        } catch (Exception ex) {
            meterRegistry.counter("redis.skip", "op", operationTag).increment();
            log.warn("[Redis][{}] Skipped. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
        }
    }

    /**
     * Chạy Redis operation với custom exception handler khi fail.
     * <p>
     * Dùng khi cần thực hiện hành động cụ thể khi Redis fail
     * (e.g., pushToStream fail → direct DB insert fallback).
     *
     * @param redisOp      Redis operation cần thực thi
     * @param onFailure    hành động khi Redis fail (nhận exception làm parameter)
     * @param operationTag tên operation cho logging và Prometheus metrics
     */
    public void tryExecuteOrElse(Runnable redisOp, Runnable onFailure, String operationTag) {
        Runnable decorated = CircuitBreaker.decorateRunnable(circuitBreaker, redisOp);
        try {
            decorated.run();
        } catch (Exception ex) {
            meterRegistry.counter("redis.fallback", "op", operationTag).increment();
            log.warn("[Redis][{}] Falling back to alternative. CB state={}, reason={}",
                    operationTag, circuitBreaker.getState(), ex.getMessage());
            onFailure.run();
        }
    }

    /**
     * Lấy trạng thái hiện tại của CircuitBreaker.
     * Dùng cho health monitoring và quyết định gửi SYSTEM_WARNING cho user.
     */
    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }
}
