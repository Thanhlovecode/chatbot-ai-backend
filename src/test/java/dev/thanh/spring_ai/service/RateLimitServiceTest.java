package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RateLimitProperties;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.exception.RateLimitException;
import dev.thanh.spring_ai.utils.SafeRedisExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RateLimitService — Unit Tests")
class RateLimitServiceTest {

        @Mock
        private StringRedisTemplate redisTemplate;

        @Mock
        private RateLimitProperties props;

        @Mock
        private SafeRedisExecutor safeRedis;

        @InjectMocks
        private RateLimitService rateLimitService;

        private static final String USER_ID = "user-abc-123";

        @BeforeEach
        void setUp() {
                when(props.getBucketCapacity()).thenReturn(10);
                when(props.getRefillRatePerSecond()).thenReturn(1);
                when(props.getDailyTokenLimit()).thenReturn(10000L);
        }

        /**
         * Configure SafeRedisExecutor to pass-through to the actual Redis supplier.
         * Call this at the start of tests that need normal Redis operation behavior.
         */
        @SuppressWarnings("unchecked")
        private void stubSafeRedisPassThrough() {
                when(safeRedis.executeWithFallback(any(Supplier.class), any(Supplier.class), anyString()))
                        .thenAnswer(invocation -> {
                                Supplier<?> supplier = invocation.getArgument(0);
                                return supplier.get();
                        });
        }

        /**
         * Configure SafeRedisExecutor to invoke the fallback supplier (simulating Redis down).
         */
        @SuppressWarnings("unchecked")
        private void stubSafeRedisFallback() {
                when(safeRedis.executeWithFallback(any(Supplier.class), any(Supplier.class), anyString()))
                        .thenAnswer(invocation -> {
                                Supplier<?> fallback = invocation.getArgument(1);
                                return fallback.get();
                        });
        }

        // ─────────────────────────────────────────────────────────
        // Layer 1: Token Bucket
        // ─────────────────────────────────────────────────────────

        @Test
        @DisplayName("checkTokenBucket — when allowed — should not throw")
        void checkTokenBucket_WhenAllowed_ShouldNotThrow() {
                // Given: Redis returns [1 (allowed), 5 (tokensLeft), 0 (retryAfterSec)]
                stubSafeRedisPassThrough();
                when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                                .thenReturn(List.of(1L, 5L, 0L));

                // When / Then
                assertThatCode(() -> rateLimitService.checkTokenBucket(USER_ID))
                                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("checkTokenBucket — when blocked — should throw RateLimitException with TOO_MANY_REQUESTS")
        void checkTokenBucket_WhenBlocked_ShouldThrowRateLimitException() {
                // Given: Redis returns [0 (blocked), 0 (tokensLeft), 10 (retry after 10s)]
                stubSafeRedisPassThrough();
                when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                                .thenReturn(List.of(0L, 0L, 10L));

                // When / Then
                assertThatThrownBy(() -> rateLimitService.checkTokenBucket(USER_ID))
                                .isInstanceOf(RateLimitException.class)
                                .satisfies(ex -> {
                                        RateLimitException rle = (RateLimitException) ex;
                                        assertThat(rle.getErrorCode()).isEqualTo(RateLimitErrorCode.TOO_MANY_REQUESTS);
                                        assertThat(rle.getRetryAfterSeconds()).isEqualTo(10L);
                                });
        }

        @Test
        @DisplayName("checkTokenBucket — when Redis returns null — should fail-open (no exception)")
        void checkTokenBucket_WhenRedisReturnsNull_ShouldFailOpen() {
                // Given: SafeRedisExecutor returns fallback (null) when Redis fails
                stubSafeRedisFallback();

                // When / Then — fail-open: no exception thrown
                assertThatCode(() -> rateLimitService.checkTokenBucket(USER_ID))
                                .doesNotThrowAnyException();
        }

        // ─────────────────────────────────────────────────────────
        // Layer 2: Daily Token Quota
        // ─────────────────────────────────────────────────────────

        @Test
        @DisplayName("checkDailyTokenQuota — when within limit — should not throw")
        void checkDailyTokenQuota_WhenWithinLimit_ShouldNotThrow() {
                // Given: Redis GET returns "100" (well under limit of 10000)
                stubSafeRedisPassThrough();
                var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.get(anyString())).thenReturn("100");

                // When / Then
                assertThatCode(() -> rateLimitService.checkDailyTokenQuota(USER_ID))
                                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("checkDailyTokenQuota — when exceeded — should throw RateLimitException with DAILY_TOKEN_LIMIT_EXCEEDED")
        void checkDailyTokenQuota_WhenExceeded_ShouldThrowRateLimitException() {
                // Given: Redis GET returns "10000" (equals daily limit)
                stubSafeRedisPassThrough();
                var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.get(anyString())).thenReturn("10000");

                // When / Then
                assertThatThrownBy(() -> rateLimitService.checkDailyTokenQuota(USER_ID))
                                .isInstanceOf(RateLimitException.class)
                                .satisfies(ex -> {
                                        RateLimitException rle = (RateLimitException) ex;
                                        assertThat(rle.getErrorCode())
                                                        .isEqualTo(RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED);
                                        assertThat(rle.getTokenUsed()).isEqualTo(10000L);
                                        assertThat(rle.getTokenLimit()).isEqualTo(10000L);
                                });
        }

        @Test
        @DisplayName("checkDailyTokenQuota — when Redis returns null — should fail-open (no exception)")
        void checkDailyTokenQuota_WhenRedisReturnsNull_ShouldFailOpen() {
                // Given: SafeRedisExecutor returns fallback (null) when Redis fails
                stubSafeRedisFallback();

                // When / Then
                assertThatCode(() -> rateLimitService.checkDailyTokenQuota(USER_ID))
                                .doesNotThrowAnyException();
        }
}
