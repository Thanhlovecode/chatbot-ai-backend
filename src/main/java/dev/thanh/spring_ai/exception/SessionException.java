package dev.thanh.spring_ai.exception;

import dev.thanh.spring_ai.enums.SessionErrorCode;
import lombok.Getter;

/**
 * Custom exception cho các lỗi liên quan đến Session.
 * Mang SessionErrorCode để GlobalExceptionHandler trả về HTTP status chính xác.
 */
@Getter
public class SessionException extends RuntimeException {

    private final SessionErrorCode errorCode;

    public SessionException(SessionErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SessionException(SessionErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
