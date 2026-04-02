package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RateLimitProperties;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.exception.RateLimitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j(topic = "RATE-LIMIT")
@RequiredArgsConstructor
@SuppressWarnings("rawtypes")
public class RateLimitService {

    private static final String BUCKET_KEY_PREFIX = "rate_limit:bucket:";
    private static final String DAILY_TOKENS_KEY_PREFIX = "rate_limit:tokens:daily:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties props;

    // ─────────────────────────────────────────────────────────────────────────
    // Lua Script 1: Token Bucket (atomic refill + consume)
    // Returns: [allowed(1/0), tokensLeft, retryAfterSeconds]
    // ─────────────────────────────────────────────────────────────────────────
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT;

    // ─────────────────────────────────────────────────────────────────────────
    // Lua Script 2: Daily Token Quota — atomic check + increment
    // Returns: [allowed(1/0), dailyUsed, dailyLimit]
    // ─────────────────────────────────────────────────────────────────────────
    private static final DefaultRedisScript<List> DAILY_QUOTA_SCRIPT;

    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setResultType(List.class);
        TOKEN_BUCKET_SCRIPT.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));

        DAILY_QUOTA_SCRIPT = new DefaultRedisScript<>();
        DAILY_QUOTA_SCRIPT.setResultType(List.class);
        DAILY_QUOTA_SCRIPT.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/daily_quota.lua")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────



    // ─────────────────────────────────────────────────────────────────────────
    // Layer 1: Token Bucket
    // ─────────────────────────────────────────────────────────────────────────

    public void checkTokenBucket(String userId) {
        String bucketKey = BUCKET_KEY_PREFIX + userId;
        long nowMs = System.currentTimeMillis();

        List result = redisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(bucketKey),
                String.valueOf(props.getBucketCapacity()),
                String.valueOf(props.getRefillRatePerSecond()),
                String.valueOf(nowMs));

        if (result == null) {
            log.warn("Token bucket Redis call returned null for user={}, fail-open", userId);
            return;
        }

        long allowed = toLong(result.get(0));
        long tokensLeft = toLong(result.get(1));
        long retryAfterSec = toLong(result.get(2));

        if (allowed == 0) {
            log.warn("Layer 1 BLOCKED user={} retryAfter={}s", userId, retryAfterSec);
            throw new RateLimitException(RateLimitErrorCode.TOO_MANY_REQUESTS, retryAfterSec);
        }
        log.debug("Layer 1 OK user={} tokensLeft={}", userId, tokensLeft);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 2: Daily Token Quota
    // ─────────────────────────────────────────────────────────────────────────

    public void checkDailyTokenQuota(String userId, int inputTokens) {
        String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
        String dailyKey = DAILY_TOKENS_KEY_PREFIX + userId + ":" + today;

        // TTL = 25h (1h buffer to avoid midnight edge-cases)
        long dailyTtlSeconds = 25L * 60 * 60;

        List result = redisTemplate.execute(
                DAILY_QUOTA_SCRIPT,
                List.of(dailyKey),
                String.valueOf(props.getDailyTokenLimit()),
                String.valueOf(inputTokens),
                String.valueOf(dailyTtlSeconds));

        if (result == null) {
            log.warn("Daily quota Redis call returned null for user={}, fail-open", userId);
            return;
        }

        long allowed = toLong(result.get(0));
        long dailyUsed = toLong(result.get(1));
        long dailyLimit = toLong(result.get(2));

        if (allowed == 0) {
            log.warn("Layer 2 BLOCKED user={} dailyUsed={} dailyLimit={} requested={}",
                    userId, dailyUsed, dailyLimit, inputTokens);
            throw new RateLimitException(RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED,
                    dailyUsed, dailyLimit);
        }
        log.debug("Layer 2 OK user={} dailyUsed={}/{}", userId, dailyUsed, dailyLimit);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private long toLong(Object val) {
        if (val instanceof Number)
            return ((Number) val).longValue();
        if (val instanceof String)
            return Long.parseLong((String) val);
        return 0L;
    }
}
