package dev.thanh.spring_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rate Limiting configuration properties.
 * <p>
 * Layer 1 - Token Bucket: controls request rate per user (anti-server-crash).
 * Layer 2 - Daily Token Quota: controls AI token consumption per day (cost control).
 */
@Configuration
@ConfigurationProperties(prefix = "rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    /**
     * Layer 1 - Token Bucket
     * Maximum tokens in the bucket (burst capacity).
     */
    private int bucketCapacity = 5;

    /**
     * Layer 1 - Token Bucket
     * Number of tokens refilled per second.
     */
    private int refillRatePerSecond = 1;

    /**
     * Layer 2 - Daily Token Quota
     * Maximum input tokens allowed per user per day.
     * Default: 100,000 tokens ≈ 50-70 conversations.
     */
    private long dailyTokenLimit = 200_000L;
}
