package dev.thanh.spring_ai.exception;

/**
 * Thrown when the client sends an invalid or unrecognizable request parameter
 * (e.g. an unknown enum value). Maps to HTTP 400 Bad Request.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
