package dev.thanh.spring_ai.utils;

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

/**
 * Unit tests for SafeRedisExecutor — covers Circuit Breaker integration,
 * fallback invocation, skip behavior, and Prometheus metric increments.
 *
 * Strategy: Use a sensitiveRegistry with aggressive CB thresholds
 * (1 failure = OPEN) to easily test state transitions.
 */
@DisplayName("SafeRedisExecutor — Circuit Breaker Integration Tests")
class SafeRedisExecutorTest {

    private SafeRedisExecutor safeRedis;
    private CircuitBreakerRegistry cbRegistry;
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
        safeRedis = new SafeRedisExecutor(cbRegistry, meterRegistry);
    }

    private CircuitBreaker getCB() {
        return cbRegistry.circuitBreaker("redis");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // executeWithFallback
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("executeWithFallback")
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
    @DisplayName("tryExecute")
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
    // tryExecuteOrElse — with custom failure handler
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tryExecuteOrElse")
    class TryExecuteOrElseTests {

        @Test
        @DisplayName("when Redis succeeds — should NOT invoke onFailure")
        void whenSuccess_ShouldNotInvokeOnFailure() {
            AtomicBoolean failureCalled = new AtomicBoolean(false);

            safeRedis.tryExecuteOrElse(
                    () -> {}, // success
                    () -> failureCalled.set(true),
                    "pushToStream"
            );

            assertThat(failureCalled.get()).isFalse();
        }

        @Test
        @DisplayName("when Redis fails — should invoke onFailure (e.g., direct DB insert)")
        void whenFails_ShouldInvokeOnFailure() {
            AtomicBoolean dbInsertCalled = new AtomicBoolean(false);

            safeRedis.tryExecuteOrElse(
                    () -> { throw new RuntimeException("Redis XADD failed"); },
                    () -> dbInsertCalled.set(true),
                    "pushToStream"
            );

            assertThat(dbInsertCalled.get()).isTrue();
            Counter counter = meterRegistry.find("redis.fallback").tag("op", "pushToStream").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("when CB is OPEN — should invoke onFailure without calling Redis")
        void whenCBOpen_ShouldInvokeOnFailureDirectly() {
            getCB().transitionToOpenState();
            AtomicBoolean redisCalled = new AtomicBoolean(false);
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);

            safeRedis.tryExecuteOrElse(
                    () -> redisCalled.set(true),
                    () -> fallbackCalled.set(true),
                    "pushToStream"
            );

            assertThat(redisCalled.get()).isFalse();
            assertThat(fallbackCalled.get()).isTrue();
        }
    }
}

