package dev.thanh.spring_ai.exception;

import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import lombok.Getter;

/**
 * Exception thrown when a rate limit or token quota is exceeded.
 */
@Getter
public class RateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final RateLimitErrorCode errorCode;

    /**
     * For Layer 1 (Token Bucket): carries retry-after seconds.
     */
    private final long retryAfterSeconds;

    /**
     * For Layer 2 (Daily token quota): tokens used and limit.
     */
    private final long tokenUsed;
    private final long tokenLimit;

    /**
     * Constructor for Layer 1 - rate limit (Token Bucket).
     */
    public RateLimitException(RateLimitErrorCode errorCode, long retryAfterSeconds) {
        super(String.format(errorCode.getMessageTemplate(), retryAfterSeconds));
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.tokenUsed = 0L;
        this.tokenLimit = 0L;
    }

    /**
     * Constructor for Layer 2 - daily token quota.
     */
    public RateLimitException(RateLimitErrorCode errorCode, long tokenUsed, long tokenLimit) {
        super(String.format(errorCode.getMessageTemplate(), tokenUsed, tokenLimit));
        this.errorCode = errorCode;
        this.retryAfterSeconds = 0L;
        this.tokenUsed = tokenUsed;
        this.tokenLimit = tokenLimit;
    }
}
