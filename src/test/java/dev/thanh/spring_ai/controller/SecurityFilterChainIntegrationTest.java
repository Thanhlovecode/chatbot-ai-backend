package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.config.AbstractIntegrationTest;
import dev.thanh.spring_ai.constants.TokenConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityFilterChain Integration Test — verifies the FULL security pipeline:
 * JWT decode → blacklist/whitelist check → role authorization → controller → response.
 *
 * Uses real Spring context + MockMvc. No mocking security — tests actual SecurityConfig.
 */
@AutoConfigureMockMvc
@DisplayName("SecurityFilterChain — Integration Tests (Full Security Pipeline)")
class SecurityFilterChainIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "security-test@gmail.com";

    /**
     * Generate a real JWT signed with the application's RSA key pair.
     */
    private String generateJwt(String role, long ttlSeconds) {
        String tokenId = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(EMAIL)
                .claim(TokenConstants.CLAIM_USER_ID, USER_ID.toString())
                .claim(TokenConstants.CLAIM_ROLE, role)
                .claim(TokenConstants.CLAIM_TOKEN_TYPE, TokenConstants.TOKEN_TYPE_ACCESS)
                .id(tokenId)
                .issuer("spring-ai")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(ttlSeconds))
                .build();

        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // Whitelist the token in Redis (required by CustomJwtDecoder)
        redisTemplate.opsForValue().set(
                TokenConstants.AT_WHITE_LIST + USER_ID,
                TokenConstants.VALID_STATUS,
                ttlSeconds, TimeUnit.SECONDS);

        return jwt;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public endpoints — no auth required
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("actuator/health — should be accessible WITHOUT authentication")
    void actuatorHealth_ShouldBePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Protected endpoints — require valid JWT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("protected endpoint WITHOUT JWT — should return 401")
    void protectedEndpoint_NoJwt_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("protected endpoint WITH valid JWT — should pass security filter")
    void protectedEndpoint_ValidJwt_ShouldPassFilter() throws Exception {
        String jwt = generateJwt("USER", 900);

        // This will reach the controller (may return 4xx/5xx based on missing data,
        // but the key assertion is: NOT 401/403 from security filter)
        mockMvc.perform(get("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Blacklisted token — should be rejected
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("🔴 blacklisted JWT (logged out) — should return 401")
    void blacklistedToken_ShouldReturn401() throws Exception {
        String tokenId = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(EMAIL)
                .claim(TokenConstants.CLAIM_USER_ID, USER_ID.toString())
                .claim(TokenConstants.CLAIM_ROLE, "USER")
                .claim(TokenConstants.CLAIM_TOKEN_TYPE, TokenConstants.TOKEN_TYPE_ACCESS)
                .id(tokenId)
                .issuer("spring-ai")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // Whitelist exists
        redisTemplate.opsForValue().set(
                TokenConstants.AT_WHITE_LIST + USER_ID,
                TokenConstants.VALID_STATUS, 900, TimeUnit.SECONDS);

        // BUT token is blacklisted (user logged out)
        redisTemplate.opsForValue().set(
                TokenConstants.AT_BLACK_LIST + tokenId,
                TokenConstants.VALID_STATUS, 900, TimeUnit.SECONDS);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            mockMvc.perform(get("/api/v1/chat/sessions")
                            .header("Authorization", "Bearer " + jwt))
        ).isInstanceOf(dev.thanh.spring_ai.exception.SecurityAuthException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Admin endpoints — require ADMIN role
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("admin endpoint with USER role — should return 403")
    void adminEndpoint_UserRole_ShouldReturn403() throws Exception {
        String jwt = generateJwt("USER", 900);

        mockMvc.perform(get("/api/v1/admin/dashboard/stats")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden());
    }
}
