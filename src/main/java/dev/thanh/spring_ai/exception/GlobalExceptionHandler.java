package dev.thanh.spring_ai.exception;

import dev.thanh.spring_ai.dto.response.ResponseData;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import jakarta.validation.ConstraintViolationException;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Bỏ qua lỗi khi Client (Browser/k6) chủ động ngắt kết nối lúc đang Streaming SSE.
     * Ngăn chặn bão Log (Log Storm) làm kẹt CPU.
     */
    @ExceptionHandler({
        AsyncRequestNotUsableException.class,
        ClientAbortException.class
    })
    public void handleClientAbortException(Exception ex) {
        // KHÔNG in ra stack trace (không truyền 'ex' vào log.warn).
        // Chỉ in 1 dòng WARN ngắn gọn. Nếu test 5000 VU, bạn có thể comment luôn dòng này để tắt hẳn log.
        log.warn("Client abruptly disconnected mid-stream (Connection reset). Ignoring.");
    }

    /**
     * Bắt thêm IOException thuần túy để chặn các log rác về Network Pipe
     */
    @ExceptionHandler(IOException.class)
    public void handleIoException(IOException ex) {
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Connection reset by peer") || msg.contains("Broken pipe"))) {
            log.warn("Network pipe broken (Client disconnected).");
        } else {
            // Các lỗi IO nghiêm trọng khác (như hỏng ổ cứng, file) thì mới log error
            log.error("Unexpected IO Error: ", ex);
        }
    }

    /**
     * Bắt lỗi Bean Validation trên @RequestParam / @PathVariable
     * (trigger bởi @Validated trên class + @Min/@Max trên parameter).
     * Khác với MethodArgumentNotValidException (chỉ cho @RequestBody DTO).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseData<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            // "getSessions.limit" → "limit"
            String paramName = violation.getPropertyPath().toString();
            if (paramName.contains(".")) {
                paramName = paramName.substring(paramName.lastIndexOf('.') + 1);
            }
            errors.put(paramName, violation.getMessage());
        });

        log.warn("Constraint violation: {}", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseData.<Map<String, String>>builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .message("Validation failed")
                        .data(errors)
                        .timestamp(ZonedDateTime.now())
                        .build());
    }

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

    @ExceptionHandler(ServiceDegradedException.class)
    public ResponseEntity<ResponseData<Void>> handleServiceDegradedException(ServiceDegradedException ex) {
        log.warn("Service degraded (Redis CB OPEN): {}", ex.getMessage());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "30");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(ResponseData.<Void>builder()
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
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

