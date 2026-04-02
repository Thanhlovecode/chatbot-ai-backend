package dev.thanh.spring_ai.config;



import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Type-safe configuration properties for retry logic with exponential backoff.
 */
@Component
@ConfigurationProperties(prefix = "retry")
@Validated
@Getter
@Setter
public class RetryProperties {

    /**
     * Initial backoff interval in milliseconds.
     * First retry happens after this delay.
     */
    @Min(value = 100, message = "Initial interval must be at least 100ms")
    private long initialIntervalMs = 1000;

    /**
     * Backoff multiplier for exponential backoff.
     * Each retry waits:  previousWait * multiplier
     * Example: 1000ms -> 2000ms -> 4000ms (multiplier = 2.0)
     */
    @Min(value = 1, message = "Multiplier must be at least 1.0")
    private double multiplier = 2.0;

    /**
     * Maximum backoff interval in milliseconds.
     * Caps exponential growth to prevent infinite waits.
     */
    @Min(value = 1000, message = "Max interval must be at least 1 second")
    private long maxIntervalMs = 60000;
}