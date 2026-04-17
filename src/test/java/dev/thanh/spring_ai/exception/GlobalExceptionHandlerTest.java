package dev.thanh.spring_ai.exception;

import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.enums.SecurityErrorCode;
import dev.thanh.spring_ai.enums.SessionErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler unit tests — verifies:
 * 1. OWASP: Stack traces are NEVER leaked to clients
 * 2. Correct HTTP status codes for each exception type
 * 3. Rate limit headers (Retry-After, X-RateLimit-*)
 * 4. Generic exception catch-all returns safe message
 */
@DisplayName("GlobalExceptionHandler — Unit Tests (OWASP Compliance)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ═══════════════════════════════════════════════════════════════════════
    // SECURITY: Stack trace leak prevention (OWASP Top 10)
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("OWASP — Stack Trace Leak Prevention")
    class StackTraceLeakTests {

        @Test
        @DisplayName("RuntimeException — should return 500 with generic message, NO stack trace")
        void runtimeException_ShouldNotLeakStackTrace() {
            // Given: exception with internal details
            RuntimeException ex = new RuntimeException("SQL Error: Table 'users' column 'password' is null");

            // When
            ResponseEntity<ResponseData<Void>> response = handler.handleRuntimeException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(500);
            // SECURITY: message must NOT contain internal details
            assertThat(response.getBody().getMessage())
                    .doesNotContain("SQL Error")
                    .doesNotContain("Table")
                    .doesNotContain("password")
                    .contains("internal error");
        }

        @Test
        @DisplayName("generic Exception — should return 500 with safe message, NO exception details")
        void genericException_ShouldNotLeakDetails() {
            // Given: unexpected exception with sensitive info
            Exception ex = new Exception("NullPointerException at UserService.java:42");

            // When
            ResponseEntity<ResponseData<Void>> response = handler.handleGenericException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage())
                    .doesNotContain("NullPointerException")
                    .doesNotContain("UserService")
                    .contains("unexpected error");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SecurityAuthException → correct HTTP status codes
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SecurityAuthException")
    class SecurityAuthExceptionTests {

        @Test
        @DisplayName("INVALID_TOKEN — should return 401")
        void invalidToken_ShouldReturn401() {
            SecurityAuthException ex = new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);

            ResponseEntity<ResponseData<Void>> response = handler.handleSecurityException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody().getMessage()).isEqualTo(SecurityErrorCode.INVALID_TOKEN.getMessage());
        }

        @Test
        @DisplayName("GOOGLE_TOKEN_INVALID — should return 401")
        void googleTokenInvalid_ShouldReturn401() {
            SecurityAuthException ex = new SecurityAuthException(SecurityErrorCode.GOOGLE_TOKEN_INVALID);

            ResponseEntity<ResponseData<Void>> response = handler.handleSecurityException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        @DisplayName("EMAIL_NOT_VERIFIED — should return 403")
        void emailNotVerified_ShouldReturn403() {
            SecurityAuthException ex = new SecurityAuthException(SecurityErrorCode.EMAIL_NOT_VERIFIED);

            ResponseEntity<ResponseData<Void>> response = handler.handleSecurityException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("ACCESS_DENIED — should return 403")
        void accessDenied_ShouldReturn403() {
            SecurityAuthException ex = new SecurityAuthException(SecurityErrorCode.ACCESS_DENIED);

            ResponseEntity<ResponseData<Void>> response = handler.handleSecurityException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SessionException → correct HTTP status codes
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SessionException")
    class SessionExceptionTests {

        @Test
        @DisplayName("SESSION_NOT_FOUND — should return 404")
        void sessionNotFound_ShouldReturn404() {
            SessionException ex = new SessionException(SessionErrorCode.SESSION_NOT_FOUND);

            ResponseEntity<ResponseData<Void>> response = handler.handleSessionException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("SESSION_INACTIVE — should return 410")
        void sessionInactive_ShouldReturn410() {
            SessionException ex = new SessionException(SessionErrorCode.SESSION_INACTIVE);

            ResponseEntity<ResponseData<Void>> response = handler.handleSessionException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(410);
        }

        @Test
        @DisplayName("SESSION_ACCESS_DENIED — should return 403")
        void sessionAccessDenied_ShouldReturn403() {
            SessionException ex = new SessionException(SessionErrorCode.SESSION_ACCESS_DENIED);

            ResponseEntity<ResponseData<Void>> response = handler.handleSessionException(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RateLimitException → HTTP 429 with headers
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RateLimitException — HTTP 429 + headers")
    class RateLimitExceptionTests {

        @Test
        @DisplayName("Layer 1 (Token Bucket) — should return 429 with Retry-After header")
        void layer1_ShouldReturn429WithRetryAfter() {
            RateLimitException ex = new RateLimitException(RateLimitErrorCode.TOO_MANY_REQUESTS, 10L);

            ResponseEntity<ResponseData<Void>> response = handler.handleRateLimitException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("10");
            assertThat(response.getBody().getMessage()).contains("10");
        }

        @Test
        @DisplayName("Layer 2 (Daily Quota) — should return 429 with X-RateLimit headers")
        void layer2_ShouldReturn429WithQuotaHeaders() {
            RateLimitException ex = new RateLimitException(
                    RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED, 10000L, 10000L);

            ResponseEntity<ResponseData<Void>> response = handler.handleRateLimitException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst("X-RateLimit-Daily-Limit")).isEqualTo("10000");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Daily-Used")).isEqualTo("10000");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BadRequestException → 400
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("BadRequestException — should return 400 with message")
    void badRequest_ShouldReturn400() {
        BadRequestException ex = new BadRequestException("Invalid input data");

        ResponseEntity<ResponseData<Void>> response = handler.handleBadRequestException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid input data");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ResourceNotFoundException → 404
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("ResourceNotFoundException — should return 404")
    void resourceNotFound_ShouldReturn404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Session", "id", "abc-123");

        ResponseEntity<ResponseData<Void>> response = handler.handleResourceNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).contains("Session");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ServiceDegradedException → 503 with Retry-After
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("ServiceDegradedException — should return 503 with Retry-After=30")
    void serviceDegraded_ShouldReturn503WithRetryAfter() {
        ServiceDegradedException ex = new ServiceDegradedException("Hệ thống đang quá tải");

        ResponseEntity<ResponseData<Void>> response = handler.handleServiceDegradedException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(response.getBody().getMessage()).contains("quá tải");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Client disconnect — silent handling
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Client disconnect — silent handling")
    class ClientDisconnectTests {

        @Test
        @DisplayName("ClientAbortException — should be silently ignored (no crash, no response)")
        void clientAbort_ShouldBeSilentlyIgnored() {
            // Given
            org.apache.catalina.connector.ClientAbortException ex =
                    new org.apache.catalina.connector.ClientAbortException("Connection reset");

            // When / Then: should not throw, returns void
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> handler.handleClientAbortException(ex));
        }

        @Test
        @DisplayName("IOException with 'Broken pipe' — should be silently ignored")
        void brokenPipe_ShouldBeSilentlyIgnored() {
            java.io.IOException ex = new java.io.IOException("Broken pipe");

            // When / Then: should not throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> handler.handleIoException(ex));
        }

        @Test
        @DisplayName("IOException with 'Connection reset by peer' — should be silently ignored")
        void connectionReset_ShouldBeSilentlyIgnored() {
            java.io.IOException ex = new java.io.IOException("Connection reset by peer");

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> handler.handleIoException(ex));
        }
    }
}
