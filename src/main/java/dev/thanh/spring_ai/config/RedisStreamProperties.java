package dev.thanh.spring_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Component
@ConfigurationProperties(prefix = "redis.stream")
@Validated
@Getter
@Setter
public class RedisStreamProperties {

    /**
     * Redis Stream name for chat messages.
     * Example: chat: messages
     */
    @NotBlank(message = "Stream name is required")
    private String name;

    /**
     * Consumer group name for distributed processing.
     * Example: chat-processor-group
     */
    @NotBlank(message = "Consumer group is required")
    private String consumerGroup;

    /**
     * Unique consumer name for this worker instance.
     * Pattern: worker-{hostname}-{uuid}
     */
    @NotBlank(message = "Consumer name is required")
    private String consumerName;

    /**
     * Dead Letter Queue stream name for poison messages.
     * Example: chat:messages: dlq
     */
    @NotBlank(message = "Dead letter stream is required")
    private String deadLetterStream;

    /**
     * Maximum number of messages to read per batch.
     * Range: 100-1000
     * Recommended: 500 for optimal performance
     */
    @Min(value = 1, message = "Batch size must be at least 1")
    private int batchSize = 500;

    /**
     * Block duration in milliseconds when reading from stream.
     * 0 = non-blocking, >0 = wait up to N ms
     */
    @Min(value = 0, message = "Block duration cannot be negative")
    private long blockDurationMs = 1000;

    /**
     * Scheduler interval in milliseconds for main consumption loop.
     * Recommended: 200ms for real-time processing
     */
    @Positive(message = "Scheduler interval must be positive")
    private long schedulerIntervalMs = 200;

    /**
     * Maximum retry attempts before moving to DLQ.
     * Recommended: 3
     */
    @Min(value = 1, message = "Max retry attempts must be at least 1")
    private int maxRetryAttempts = 3;

    /**
     * Interval in milliseconds for checking pending messages.
     * Recommended: 30000 (30 seconds)
     */
    @Positive(message = "Pending check interval must be positive")
    private long pendingCheckIntervalMs = 30000;

    /**
     * Idle threshold in milliseconds for considering a message stale.
     * Messages idle longer than this will be reclaimed.
     * Recommended: 300000 (5 minutes)
     */
    @Positive(message = "Pending idle threshold must be positive")
    private long pendingIdleThresholdMs = 300000;
    /**
     * Minimum idle time in milliseconds before a message can be claimed.
     * Recommended: 300000 (5 minutes)
     */
    @Positive(message = "Claim min idle time must be positive")
    private long claimMinIdleTimeMs = 300000;
}