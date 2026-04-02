package dev.thanh.spring_ai.dto.request;

import lombok.Builder;
import lombok.Data;

/**
 * Metadata for tracking message processing lifecycle.
 */
@Data
@Builder
public class StreamMessageMetadata {
    private String messageId;
    private String consumerId;
    private int deliveryCount;
    private long idleTimeMs;
    private int retryAttempt;
    private Throwable lastError;
}