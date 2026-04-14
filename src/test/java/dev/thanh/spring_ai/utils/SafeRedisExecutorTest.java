package dev.thanh.spring_ai.utils;

import dev.thanh.spring_ai.exception.ServiceDegradedException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SafeRedisExecutor — covers Circuit Breaker integration,
 * fallback invocation, skip behavior, Prometheus metric increments,
 * DB Bulkhead protection, and operation priority classification.
 *
 * Strategy: Use a sensitiveRegistry with aggressive CB thresholds
 * (1 failure = OPEN) to easily test state transitions.
 */
@DisplayName("SafeRedisExecutor — Circuit Breaker + Bulkhead Integration Tests")
class SafeRedisExecutorTest {

    private SafeRedisExecutor safeRedis;
    private CircuitBreakerRegistry cbRegistry;
    private BulkheadRegistry bulkheadRegistry;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(1)
                        .failureRateThreshold(100) // 1 failure = OPEN
                        .waitDurationInOpenState(Duration.ofSeconds(60))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .recordExceptions(RuntimeException.class)
                        .build()
        );
        bulkheadRegistry = BulkheadRegistry.of(
                BulkheadConfig.custom()
                        .maxConcurrentCalls(2) // Small for testing
                        .maxWaitDuration(Duration.ofMillis(100))
                        .build()
        );
        safeRedis = new SafeRedisExecutor(cbRegistry, bulkheadRegistry, meterRegistry);
    }

    private CircuitBreaker getCB() {
        return cbRegistry.circuitBreaker("redis");
    }

    private Bulkhead getBulkhead() {
        return bulkheadRegistry.bulkhead("db-fallback");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // executeWithFallback (FAIL-OPEN)
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("executeWithFallback (fail-open)")
    class ExecuteWithFallbackTests {

        @Test
        @DisplayName("when Redis operation succeeds — should return result, CB stays CLOSED")
        void whenSuccess_ShouldReturnResult() {
            String result = safeRedis.executeWithFallback(
                    () -> "hello-from-redis",
                    () -> "fallback",
                    "testOp"
            );

            assertThat(result).isEqualTo("hello-from-redis");
            assertThat(getCB().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("when Redis operation throws — should return fallback and increment counter")
        void whenFails_ShouldReturnFallbackAndIncrementCounter() {
            String result = safeRedis.executeWithFallback(
                    () -> { throw new RuntimeException("Redis connection refused"); },
                    () -> "fallback-value",
                    "getHistory"
            );

            assertThat(result).isEqualTo("fallback-value");

            // Verify Prometheus counter incremented
            Counter counter = meterRegistry.find("redis.fallback").tag("op", "getHistory").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("when CB is OPEN — should invoke fallback immediately (fail-fast)")
        void whenCBOpen_ShouldFailFast() {
            // Force CB to OPEN
            getCB().transitionToOpenState();
            assertThat(getCB().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            AtomicBoolean redisWasCalled = new AtomicBoolean(false);

            String result = safeRedis.executeWithFallback(
                    () -> { redisWasCalled.set(true); return "should-not-reach"; },
                    () -> "fallback-fast",
                    "blockedOp"
            );

            assertThat(result).isEqualTo("fallback-fast");
            assertThat(redisWasCalled.get()).isFalse(); // Redis was NOT called
        }

        @Test
        @DisplayName("after failures — CB should transition CLOSED → OPEN")
        void afterFailures_CBShouldOpen() {
            assertThat(getCB().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // Cause 1 failure → CB should OPEN (threshold = 100%, min calls = 1)
            safeRedis.executeWithFallback(
                    () -> { throw new RuntimeException("fail"); },
                    () -> null,
                    "failOp"
            );

            assertThat(getCB().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

    }

    // ═══════════════════════════════════════════════════════════════════════
    // tryExecute — fire-and-forget
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tryExecute (fire-and-forget)")
    class TryExecuteTests {

        @Test
        @DisplayName("when Redis operation succeeds — should execute normally")
        void whenSuccess_ShouldExecute() {
            AtomicBoolean executed = new AtomicBoolean(false);

            safeRedis.tryExecute(
                    () -> executed.set(true),
                    "cacheHistory"
            );

            assertThat(executed.get()).isTrue();
        }

        @Test
        @DisplayName("when Redis operation throws — should skip silently and increment skip counter")
        void whenFails_ShouldSkipAndIncrementCounter() {
            safeRedis.tryExecute(
                    () -> { throw new RuntimeException("Redis timeout"); },
                    "trimStream"
            );

            // Should NOT throw, just log + increment counter
            Counter counter = meterRegistry.find("redis.skip").tag("op", "trimStream").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("when CB is OPEN — should skip without calling Redis")
        void whenCBOpen_ShouldSkipWithoutCalling() {
            getCB().transitionToOpenState();
            AtomicBoolean redisCalled = new AtomicBoolean(false);

            safeRedis.tryExecute(
                    () -> redisCalled.set(true),
                    "touchSession"
            );

            assertThat(redisCalled.get()).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // executeCriticalWithFallback — CRITICAL with DB Bulkhead
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("executeCriticalWithFallback (DB Bulkhead)")
    class ExecuteCriticalWithFallbackTests {

        @Test
        @DisplayName("when Redis succeeds — should return result without touching bulkhead")
        void whenSuccess_ShouldReturnResultDirectly() {
            String result = safeRedis.executeCriticalWithFallback(
                    () -> "redis-result",
                    () -> "db-fallback",
                    "getHistory"
            );

            assertThat(result).isEqualTo("redis-result");
            // Bulkhead should not have been used
            Counter acquired = meterRegistry.find("db.fallback.acquired").counter();
            assertThat(acquired).isNull();
        }

        @Test
        @DisplayName("when Redis fails — should fallback through bulkhead and increment counters")
        void whenFails_ShouldFallbackThroughBulkhead() {
            String result = safeRedis.executeCriticalWithFallback(
                    () -> { throw new RuntimeException("Redis XADD failed"); },
                    () -> "db-inserted",
                    "pushToStream"
            );

            assertThat(result).isEqualTo("db-inserted");

            // Verify both redis.fallback and db.fallback.acquired counters
            Counter fallbackCounter = meterRegistry.find("redis.fallback").tag("op", "pushToStream").counter();
            assertThat(fallbackCounter).isNotNull();
            assertThat(fallbackCounter.count()).isEqualTo(1.0);

            Counter acquiredCounter = meterRegistry.find("db.fallback.acquired").tag("op", "pushToStream").counter();
            assertThat(acquiredCounter).isNotNull();
            assertThat(acquiredCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("when CB is OPEN — should fallback through bulkhead without calling Redis")
        void whenCBOpen_ShouldFallbackThroughBulkhead() {
            getCB().transitionToOpenState();
            AtomicBoolean redisCalled = new AtomicBoolean(false);

            String result = safeRedis.executeCriticalWithFallback(
                    () -> { redisCalled.set(true); return "nope"; },
                    () -> "db-fallback",
                    "getHistory"
            );

            assertThat(redisCalled.get()).isFalse();
            assertThat(result).isEqualTo("db-fallback");
        }

        @Test
        @DisplayName("when bulkhead full — should throw ServiceDegradedException")
        void whenBulkheadFull_ShouldThrowServiceDegraded() {
            getCB().transitionToOpenState();
            Bulkhead bh = getBulkhead();

            // Exhaust all bulkhead permits (max=2)
            bh.acquirePermission();
            bh.acquirePermission();

            assertThatThrownBy(() -> safeRedis.executeCriticalWithFallback(
                    () -> "will-not-run",
                    () -> "will-not-run-either",
                    "pushToStream"
            )).isInstanceOf(ServiceDegradedException.class);

            // Verify rejected counter
            Counter rejected = meterRegistry.find("db.fallback.rejected").tag("op", "pushToStream").counter();
            assertThat(rejected).isNotNull();
            assertThat(rejected.count()).isEqualTo(1.0);

            // Cleanup
            bh.releasePermission();
            bh.releasePermission();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // tryCriticalExecuteOrElse — CRITICAL void with DB Bulkhead
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tryCriticalExecuteOrElse (DB Bulkhead void)")
    class TryCriticalExecuteOrElseTests {

        @Test
        @DisplayName("when Redis succeeds — should NOT invoke dbFallback")
        void whenSuccess_ShouldNotInvokeFallback() {
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);

            safeRedis.tryCriticalExecuteOrElse(
                    () -> {}, // success
                    () -> fallbackCalled.set(true),
                    "pushToStream"
            );

            assertThat(fallbackCalled.get()).isFalse();
        }

        @Test
        @DisplayName("when Redis fails — should invoke dbFallback through bulkhead")
        void whenFails_ShouldInvokeFallbackThroughBulkhead() {
            AtomicBoolean dbInsertCalled = new AtomicBoolean(false);

            safeRedis.tryCriticalExecuteOrElse(
                    () -> { throw new RuntimeException("Redis XADD failed"); },
                    () -> dbInsertCalled.set(true),
                    "pushToStream"
            );

            assertThat(dbInsertCalled.get()).isTrue();

            Counter counter = meterRegistry.find("db.fallback.acquired").tag("op", "pushToStream").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("when CB is OPEN — should invoke dbFallback without calling Redis")
        void whenCBOpen_ShouldInvokeFallbackDirectly() {
            getCB().transitionToOpenState();
            AtomicBoolean redisCalled = new AtomicBoolean(false);
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);

            safeRedis.tryCriticalExecuteOrElse(
                    () -> redisCalled.set(true),
                    () -> fallbackCalled.set(true),
                    "pushToStream"
            );

            assertThat(redisCalled.get()).isFalse();
            assertThat(fallbackCalled.get()).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // executeOrReject — NON-CRITICAL (reject only when CB OPEN)
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("executeOrReject (non-critical)")
    class ExecuteOrRejectTests {

        @Test
        @DisplayName("when Redis succeeds — should return result normally")
        void whenSuccess_ShouldReturnResult() {
            Long result = safeRedis.executeOrReject(
                    () -> 42L,
                    () -> 0L,
                    "getZSetSize"
            );

            assertThat(result).isEqualTo(42L);
        }

        @Test
        @DisplayName("when transient failure (CB still CLOSED) — should return safeDefault, NOT throw")
        void whenTransientFailure_ShouldReturnSafeDefault() {
            // CB requires 1 failure to open, but we test the individual failure path
            // Use a fresh CB that won't open immediately on this call
            // Actually with our config (failureRate=100%, minCalls=1), 1 failure = OPEN
            // So we need a different config for this test to show transient behavior

            // Create a more tolerant CB for this specific test
            CircuitBreakerRegistry tolerantRegistry = CircuitBreakerRegistry.of(
                    CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(5) // Need 5 calls before evaluating
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(60))
                            .recordExceptions(RuntimeException.class)
                            .build()
            );
            SafeRedisExecutor tolerantExecutor = new SafeRedisExecutor(
                    tolerantRegistry, bulkheadRegistry, meterRegistry);

            Long result = tolerantExecutor.executeOrReject(
                    () -> { throw new RuntimeException("Redis timeout"); },
                    () -> 0L,
                    "getZSetSize"
            );

            // Should return safe default, NOT throw ServiceDegradedException
            assertThat(result).isEqualTo(0L);

            // CB should still be CLOSED (not enough failures to open)
            assertThat(tolerantRegistry.circuitBreaker("redis").getState())
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("when CB is OPEN — should throw ServiceDegradedException (HTTP 503)")
        void whenCBOpen_ShouldThrowServiceDegraded() {
            getCB().transitionToOpenState();
            AtomicBoolean redisCalled = new AtomicBoolean(false);

            assertThatThrownBy(() -> safeRedis.executeOrReject(
                    () -> { redisCalled.set(true); return 42L; },
                    () -> 0L,
                    "getZSetSize"
            )).isInstanceOf(ServiceDegradedException.class)
              .hasMessageContaining("quá tải");

            assertThat(redisCalled.get()).isFalse(); // Redis was NOT called

            // Verify rejected counter
            Counter counter = meterRegistry.find("redis.rejected").tag("op", "getZSetSize").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("when CB transitions CLOSED → OPEN during failures — subsequent calls should throw")
        void whenCBTransitionsToOpen_SubsequentCallsShouldThrow() {
            // First call: CB CLOSED, failure → safe default returned, CB becomes OPEN
            Long firstResult = safeRedis.executeOrReject(
                    () -> { throw new RuntimeException("fail"); },
                    () -> 0L,
                    "getZSetSize"
            );
            assertThat(firstResult).isEqualTo(0L); // safe default

            // CB should now be OPEN (failureRate=100%, minCalls=1)
            assertThat(getCB().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // Second call: CB OPEN → should throw ServiceDegradedException
            assertThatThrownBy(() -> safeRedis.executeOrReject(
                    () -> 42L,
                    () -> 0L,
                    "getZSetSize"
            )).isInstanceOf(ServiceDegradedException.class);
        }
    }
}
