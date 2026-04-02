package dev.thanh.spring_ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thanh.spring_ai.config.security.CustomJwtAuthenticationConverter;
import dev.thanh.spring_ai.config.security.CustomJwtDecoder;
import dev.thanh.spring_ai.config.security.JwtAuthenticationEntryPoint;
import dev.thanh.spring_ai.config.security.SecurityConfig;
import dev.thanh.spring_ai.dto.request.GoogleLoginRequest;
import dev.thanh.spring_ai.service.security.AuthenticationService;
import dev.thanh.spring_ai.service.security.AuthenticationService.TokenPair;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController tests updated for Hybrid Token approach.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("unittest")
@TestPropertySource(properties = {
        "spring.ai.google.genai.api-key=test-key",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "app.cookie.secure=false",
        "security.jwt.refresh-token-duration-seconds=604800"
})
@DisplayName("AuthController — Slice Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationService authenticationService;

    // JWT security beans required by SecurityConfig
    @MockitoBean
    private CustomJwtDecoder customJwtDecoder;
    @MockitoBean
    private CustomJwtAuthenticationConverter customJwtAuthenticationConverter;
    @MockitoBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @BeforeEach
    void stubAuthEntryPoint() throws Exception {
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }).when(jwtAuthenticationEntryPoint).commence(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────
    // POST /api/v1/auth/google
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /google — valid idToken — should return 200 with access token and set refresh cookie")
    void loginWithGoogle_WhenValidRequest_ShouldReturn200() throws Exception {
        // Given
        TokenPair tokenPair = new TokenPair("access.token.jwt", "refresh.token.jwt");
        when(authenticationService.authenticateWithGoogle(any(GoogleLoginRequest.class)))
                .thenReturn(tokenPair);

        // When / Then
        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new GoogleLoginRequest("valid-google-id-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access.token.jwt"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist()) // Shouldn't be in body anymore
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().value("refresh_token", "refresh.token.jwt"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", false)); // set via test properties app.cookie.secure=false
    }

    @Test
    @DisplayName("POST /google — missing idToken — should return 400 (validation fails)")
    void loginWithGoogle_WhenRequestBodyInvalid_ShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new GoogleLoginRequest(""))))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────
    // POST /api/v1/auth/refresh
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /refresh — valid refresh cookie — should return 200 with new tokens")
    void refreshToken_WhenValidRequest_ShouldReturn200() throws Exception {
        // Given
        TokenPair newPair = new TokenPair("new.access.token", "new.refresh.token");
        when(authenticationService.refreshToken(anyString())).thenReturn(newPair);

        // When / Then
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new Cookie("refresh_token", "old.refresh.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new.access.token"))
                .andExpect(cookie().value("refresh_token", "new.refresh.token"))
                .andExpect(cookie().httpOnly("refresh_token", true));
    }

    @Test
    @DisplayName("POST /refresh — missing cookie — should return 4xx")
    void refreshToken_WhenMissingCookie_ShouldThrowException() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                // Expect client error for missing cookie/missing token
                .andExpect(status().is4xxClientError());
    }

    // ─────────────────────────────────────────────────────────
    // POST /api/v1/auth/logout
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /logout — should clear cookie and call logout service")
    void logout_ShouldReturn200AndClearCookie() throws Exception {
        // Prevent NPE in BearerTokenAuthenticationFilter by mocking a valid JWT and its conversion
        org.springframework.security.oauth2.jwt.Jwt mockJwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test.access.token")
                .header("alg", "none")
                .claim("sub", "user")
                .build();
        when(customJwtDecoder.decode("test.access.token")).thenReturn(mockJwt);
        when(customJwtAuthenticationConverter.convert(any())).thenReturn(
                new org.springframework.security.authentication.TestingAuthenticationToken("user", null, "ROLE_USER"));

        doNothing().when(authenticationService).logout("Bearer test.access.token");

        mockMvc.perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test.access.token"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().value("refresh_token", ""))
                .andExpect(cookie().maxAge("refresh_token", 0));
    }
}
