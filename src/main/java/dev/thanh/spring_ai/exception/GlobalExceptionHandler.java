package dev.thanh.spring_ai.exception;

import dev.thanh.spring_ai.dto.response.ResponseData;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseData<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.error("Validation error: {}", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseData.<Map<String, String>>builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .message("Validation failed")
                        .data(errors)
                        .build());
    }

    @ExceptionHandler(SecurityAuthException.class)
    public ResponseEntity<ResponseData<Void>> handleSecurityException(SecurityAuthException ex) {
        log.warn("Security error: {} - {}", ex.getErrorCode(), ex.getMessage());

        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(ResponseData.<Void>builder()
                        .status(ex.getErrorCode().getHttpStatus())
                        .message(ex.getMessage())
                        .timestamp(ZonedDateTime.now())
                        .build());
    }

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<ResponseData<Void>> handleSessionException(SessionException ex) {
        log.warn("Session error: {} - {}", ex.getErrorCode(), ex.getMessage());

        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(ResponseData.<Void>builder()
                        .status(ex.getErrorCode().getHttpStatus())
                        .message(ex.getMessage())
                        .timestamp(ZonedDateTime.now())
                        .build());
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ResponseData<Void>> handleRateLimitException(RateLimitException ex) {
        String message;
        HttpHeaders headers = new HttpHeaders();

        if (ex.getErrorCode().name().equals("TOO_MANY_REQUESTS")) {
            message = String.format(ex.getErrorCode().getMessageTemplate(), ex.getRetryAfterSeconds());
            headers.set("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
            log.warn("Rate limit Layer 1: {}", message);
        } else {
            message = String.format(ex.getErrorCode().getMessageTemplate(),
                    ex.getTokenUsed(), ex.getTokenLimit());
            headers.set("X-RateLimit-Daily-Limit", String.valueOf(ex.getTokenLimit()));
            headers.set("X-RateLimit-Daily-Used",  String.valueOf(ex.getTokenUsed()));
            log.warn("Rate limit Layer 2: {}", message);
        }

        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .headers(headers)
                .body(ResponseData.<Void>builder()
                        .status(ex.getErrorCode().getHttpStatus().value())
                        .message(message)
                        .timestamp(ZonedDateTime.now())
                        .build());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ResponseData<Void>> handleBadRequestException(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseData.<Void>builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .message(ex.getMessage())
                        .timestamp(ZonedDateTime.now())
                        .build());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ResponseData<Void>> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResponseData.<Void>builder()
                        .status(HttpStatus.NOT_FOUND.value())
                        .message(ex.getMessage())
                        .timestamp(ZonedDateTime.now())
                        .build());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ResponseData<Void>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime error: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseData.<Void>builder()
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .message("An internal error occurred. Please try again later.")
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseData<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseData.<Void>builder()
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .message("An unexpected error occurred. Please try again later.")
                        .build());
    }
}

