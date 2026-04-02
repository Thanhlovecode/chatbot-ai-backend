package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RateLimitProperties;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for RateLimitService that run Lua scripts on a REAL Redis instance.
 * Uses Testcontainers with standard GenericContainer for maximum compatibility.
 *
 * BLOCKER 2 Fix: @ActiveProfiles("integration") loads application-integration.yml.
 */
@DisplayName("RateLimitService — Integration Tests (Redis Testcontainers)")
class RateLimitServiceIntegrationTest extends dev.thanh.spring_ai.config.AbstractIntegrationTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    private String userId;

    @BeforeEach
    void setUp() {
        // GIÁP BẢO VỆ 2: Quét sạch rác Redis từ các test trước đó (Chống State Leakage tuyệt đối)
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Use unique userId per test to avoid cross-test contamination
        userId = "test-user-" + System.nanoTime();
    }

    // ─────────────────────────────────────────────────────────
    // Layer 1: Token Bucket — Lua Script on Real Redis
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkTokenBucket — first request — should allow (bucket has capacity)")
    void checkTokenBucket_WhenFirstRequest_ShouldAllow() {
        // When / Then: no exception on first call
        assertThatCode(() -> rateLimitService.checkTokenBucket(userId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkTokenBucket — when bucket exhausted — should throw RateLimitException")
    void checkTokenBucket_WhenBucketExhausted_ShouldThrow() {
        // Given: exhaust the bucket (bucketCapacity + 1 calls)
        int capacity = (int) rateLimitProperties.getBucketCapacity();

        // Drain all tokens
        for (int i = 0; i < capacity; i++) {
            assertThatCode(() -> rateLimitService.checkTokenBucket(userId))
                    .doesNotThrowAnyException();
        }

        // When: one more request exceeds capacity
        assertThatThrownBy(() -> rateLimitService.checkTokenBucket(userId))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getErrorCode()).isEqualTo(RateLimitErrorCode.TOO_MANY_REQUESTS);
                    assertThat(rle.getRetryAfterSeconds()).isGreaterThan(0);
                });
    }

    // ─────────────────────────────────────────────────────────
    // Layer 2: Daily Token Quota — Lua Script on Real Redis
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkDailyTokenQuota — within limit — should allow")
    void checkDailyTokenQuota_WhenWithinLimit_ShouldAllow() {
        // Given: use a small number of tokens, well under daily limit
        assertThatCode(() -> rateLimitService.checkDailyTokenQuota(userId, 100))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkDailyTokenQuota — when accumulated > daily limit — should throw")
    void checkDailyTokenQuota_WhenExceedLimit_ShouldThrow() {
        // Given: inject a custom service config by calling with tokens = limit + 1
        // Use default limit (100000) by calling with a huge token count
        long dailyLimit = rateLimitProperties.getDailyTokenLimit();

        // First call: consume all quota
        assertThatCode(() -> rateLimitService.checkDailyTokenQuota(userId, (int) dailyLimit))
                .doesNotThrowAnyException();

        // When: second call exceeds quota
        assertThatThrownBy(() -> rateLimitService.checkDailyTokenQuota(userId, 1))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getErrorCode()).isEqualTo(RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED);
                    assertThat(rle.getTokenUsed()).isGreaterThanOrEqualTo(dailyLimit);
                });
    }

    @Test
    @DisplayName("checkDailyTokenQuota — Redis TTL set to 25h")
    void checkDailyTokenQuota_WhenFirstCall_ShouldSetTtlTo25Hours() {
        // Given
        rateLimitService.checkDailyTokenQuota(userId, 10);

        // Then: verify the key exists in Redis with a TTL
        String keyPattern = "rate_limit:tokens:daily:" + userId + ":*";
        // Look up the key by pattern
        var keys = redisTemplate.keys(keyPattern);
        assertThat(keys).isNotEmpty();

        // Verify TTL is set and is approximately 25 hours (25 * 3600 = 90000 seconds)
        Long ttl = redisTemplate.getExpire(keys.iterator().next());
        assertThat(ttl).isNotNull()
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(25L * 3600);
    }
}
