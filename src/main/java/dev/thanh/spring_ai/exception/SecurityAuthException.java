package dev.thanh.spring_ai.exception;

import dev.thanh.spring_ai.enums.SecurityErrorCode;
import lombok.Getter;

/**
 * Custom exception for security/authentication errors.
 * Carries a SecurityErrorCode for structured error responses.
 */
@Getter
public class SecurityAuthException extends RuntimeException {

    private final SecurityErrorCode errorCode;

    public SecurityAuthException(SecurityErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SecurityAuthException(SecurityErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
