package dev.thanh.spring_ai.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SecurityErrorCode {

    INVALID_TOKEN(401, "Token is invalid or has been revoked"),
    TOKEN_EXPIRED(401, "Token has expired"),
    GOOGLE_TOKEN_INVALID(401, "Google ID token is invalid or expired"),
    EMAIL_NOT_VERIFIED(403, "Google email has not been verified"),
    UNAUTHORIZED(401, "Authentication required"),
    ACCESS_DENIED(403, "Access denied");

    private final int httpStatus;
    private final String message;
}
