package dev.thanh.spring_ai.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Error codes for rate limiting violations.
 */
@Getter
@RequiredArgsConstructor
public enum RateLimitErrorCode {

    /**
     * Layer 1 - Token Bucket: too many requests in a short period.
     */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Bạn đang gửi quá nhanh. Vui lòng chờ %d giây trước khi thử lại."),

    /**
     * Layer 2 - Daily token quota exceeded.
     */
    DAILY_TOKEN_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Bạn đã sử dụng hết %,d token hôm nay (giới hạn: %,d). Quota sẽ được reset vào 0h00 ngày mai.");

    private final HttpStatus httpStatus;
    private final String messageTemplate;
}
