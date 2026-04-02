package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.dto.request.GoogleLoginRequest;
import dev.thanh.spring_ai.dto.response.AuthenticationResponse;
import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.enums.SecurityErrorCode;
import dev.thanh.spring_ai.exception.SecurityAuthException;
import dev.thanh.spring_ai.service.security.AuthenticationService;
import dev.thanh.spring_ai.service.security.AuthenticationService.TokenPair;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;

/**
 * Auth endpoints.
 *
 * Token storage strategy:
 * - accessToken → JSON response body (stored in JS RAM by frontend)
 * - refreshToken → HttpOnly cookie (path=/api/v1/auth, SameSite=Lax)
 * JS cannot read it, browser sends it automatically.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

        private static final String REFRESH_COOKIE_NAME = "refresh_token";

        private final AuthenticationService authenticationService;

        @Value("${app.cookie.secure}")
        private boolean cookieSecure;

        @Value("${security.jwt.refresh-token-duration-seconds}")
        private long refreshTokenDurationSeconds;

        // ──────────────────────────────────────────────────────────────────────────
        // POST /auth/google — Google OAuth2 Login
        // ──────────────────────────────────────────────────────────────────────────

        @PostMapping("/google")
        public ResponseEntity<ResponseData<AuthenticationResponse>> loginWithGoogle(
                        @RequestBody @Valid GoogleLoginRequest request) {

                log.info("[LOGIN FLOW] Starting Google login process...");
                TokenPair pair = authenticationService.authenticateWithGoogle(request);

                ResponseCookie refreshCookie = buildRefreshCookie(pair.refreshToken(), refreshTokenDurationSeconds);
                log.info("[LOGIN FLOW] Google login successful. Built refresh cookie.");

                ResponseData<AuthenticationResponse> body = ResponseData.<AuthenticationResponse>builder()
                                .status(HttpStatus.OK.value())
                                .message("Login with Google successfully")
                                .timestamp(ZonedDateTime.now())
                                .data(new AuthenticationResponse(pair.accessToken()))
                                .build();

                return ResponseEntity.ok()
                                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                                .body(body);
        }

        // ──────────────────────────────────────────────────────────────────────────
        // POST /auth/refresh — Rotate tokens using HttpOnly cookie
        // ──────────────────────────────────────────────────────────────────────────

        @PostMapping("/refresh")
        public ResponseEntity<ResponseData<AuthenticationResponse>> refreshToken(
                        @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie) {

                log.info("[REFRESH FLOW] Starting token refresh process...");
                // Cookie not present → treat as unauthenticated
                if (refreshTokenCookie == null || refreshTokenCookie.isBlank()) {
                        log.warn("[REFRESH FLOW] Refresh token cookie is missing or blank");
                        throw new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
                }

                TokenPair pair = authenticationService.refreshToken(refreshTokenCookie);

                // Rotation: issue a new refresh cookie with the new token
                ResponseCookie newRefreshCookie = buildRefreshCookie(pair.refreshToken(), refreshTokenDurationSeconds);
                log.info("[REFRESH FLOW] Token refreshed successfully. Built new refresh cookie.");

                ResponseData<AuthenticationResponse> body = ResponseData.<AuthenticationResponse>builder()
                                .status(HttpStatus.OK.value())
                                .message("Token refreshed successfully")
                                .timestamp(ZonedDateTime.now())
                                .data(new AuthenticationResponse(pair.accessToken()))
                                .build();

                return ResponseEntity.ok()
                                .header(HttpHeaders.SET_COOKIE, newRefreshCookie.toString())
                                .body(body);
        }

        // ──────────────────────────────────────────────────────────────────────────
        // POST /auth/logout — Blacklist tokens + clear cookie
        // ──────────────────────────────────────────────────────────────────────────

        @PostMapping("/logout")
        public ResponseEntity<ResponseData<Void>> logout(
                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

                log.info("[LOGOUT FLOW] Starting logout process...");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        authenticationService.logout(authHeader);
                } else {
                        log.info("[LOGOUT FLOW] No Bearer token provided in logout request.");
                }

                // Clear the refresh_token cookie regardless of access token presence
                ResponseCookie clearCookie = buildClearCookie();

                log.info("[LOGOUT FLOW] Clear refresh token cookie on logout");

                ResponseData<Void> body = ResponseData.<Void>builder()
                                .status(HttpStatus.OK.value())
                                .message("Logged out successfully")
                                .timestamp(ZonedDateTime.now())
                                .build();

                return ResponseEntity.ok()
                                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                                .body(body);
        }

        // ──────────────────────────────────────────────────────────────────────────
        // Cookie helpers
        // ──────────────────────────────────────────────────────────────────────────

        /**
         * Build a secure HttpOnly refresh_token cookie.
         *
         * path=/api/v1/auth → browser only sends this cookie to auth endpoints,
         * NOT to /api/v1/chat/** or any other resource.
         * SameSite=Lax → CSRF protection: cookie is not sent on cross-site
         * POST requests initiated by third-party pages.
         * secure → configurable via APP_COOKIE_SECURE env var
         * (false for localhost HTTP, true for HTTPS production)
         */
        private ResponseCookie buildRefreshCookie(String refreshToken, long maxAgeSeconds) {
                return ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
                                .httpOnly(true)
                                .secure(cookieSecure)
                                .sameSite("Lax")
                                .path("/api/v1/auth")
                                .maxAge(maxAgeSeconds)
                                .build();
        }

        /**
         * Build an expiry cookie to clear refresh_token on logout.
         * Sets maxAge=0 so the browser deletes it immediately.
         */
        private ResponseCookie buildClearCookie() {
                return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                                .httpOnly(true)
                                .secure(cookieSecure)
                                .sameSite("Lax")
                                .path("/api/v1/auth")
                                .maxAge(0)
                                .build();
        }
}
