package dev.thanh.spring_ai.service.security;

import dev.thanh.spring_ai.config.security.JwtProperties;
import dev.thanh.spring_ai.constants.TokenConstants;
import dev.thanh.spring_ai.entity.User;
import dev.thanh.spring_ai.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.*;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TokenService unit tests — ensures JWT tokens contain correct claims,
 * use RS256 signing, and have proper TTL durations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService — Unit Tests (JWT Generation)")
class TokenServiceTest {

    @Mock private JwtEncoder jwtEncoder;
    @Mock private JwtProperties jwtProperties;
    @Mock private NimbusJwtDecoder jwtDecoder;

    private TokenService tokenService;

    private User testUser;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "test@gmail.com";
    private static final String TOKEN_ID = "token-id-123";

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(jwtEncoder, jwtProperties, jwtDecoder);

        testUser = User.builder()
                .id(USER_ID)
                .email(EMAIL)
                .displayName("Test User")
                .role(UserRole.USER)
                .active(true)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // createAccessToken
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createAccessToken")
    class CreateAccessTokenTests {

        @Test
        @DisplayName("should contain correct claims: subject=email, user_id, role=USER, token_type=ACCESS")
        void shouldContainCorrectClaims() {
            // Given
            when(jwtProperties.getAccessTokenDurationSeconds()).thenReturn(900L);
            when(jwtProperties.getIssuer()).thenReturn("spring-ai");
            Jwt mockJwt = Jwt.withTokenValue("encoded-access-token")
                    .header("alg", "RS256").subject(EMAIL)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900))
                    .build();
            when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt);

            // When
            String token = tokenService.createAccessToken(testUser, TOKEN_ID);

            // Then
            assertThat(token).isEqualTo("encoded-access-token");

            ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
            verify(jwtEncoder).encode(captor.capture());

            JwtClaimsSet claims = captor.getValue().getClaims();
            assertThat(claims.getSubject()).isEqualTo(EMAIL);
            assertThat((String) claims.getClaim(TokenConstants.CLAIM_USER_ID)).isEqualTo(USER_ID.toString());
            assertThat((String) claims.getClaim(TokenConstants.CLAIM_ROLE)).isEqualTo("USER");
            assertThat((String) claims.getClaim(TokenConstants.CLAIM_TOKEN_TYPE)).isEqualTo(TokenConstants.TOKEN_TYPE_ACCESS);
            assertThat(claims.getId()).isEqualTo(TOKEN_ID);
            assertThat(claims.getClaimAsString("iss")).isEqualTo("spring-ai");
        }

        @Test
        @DisplayName("should set TTL = accessTokenDurationSeconds (15 min default)")
        void shouldSetCorrectTtl() {
            // Given
            when(jwtProperties.getAccessTokenDurationSeconds()).thenReturn(900L);
            when(jwtProperties.getIssuer()).thenReturn("spring-ai");
            when(jwtEncoder.encode(any())).thenReturn(
                    Jwt.withTokenValue("t").header("alg", "RS256").subject(EMAIL)
                            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());

            // When
            tokenService.createAccessToken(testUser, TOKEN_ID);

            // Then
            ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
            verify(jwtEncoder).encode(captor.capture());

            JwtClaimsSet claims = captor.getValue().getClaims();
            long durationSec = claims.getExpiresAt().getEpochSecond() - claims.getIssuedAt().getEpochSecond();
            assertThat(durationSec).isEqualTo(900L);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // createRefreshToken
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("createRefreshToken")
    class CreateRefreshTokenTests {

        @Test
        @DisplayName("should have token_type=REFRESH and TTL = refreshTokenDurationSeconds (7 days)")
        void shouldHaveRefreshTypeAndLongTtl() {
            // Given
            when(jwtProperties.getRefreshTokenDurationSeconds()).thenReturn(604800L);
            when(jwtProperties.getIssuer()).thenReturn("spring-ai");
            when(jwtEncoder.encode(any())).thenReturn(
                    Jwt.withTokenValue("encoded-refresh-token").header("alg", "RS256").subject(EMAIL)
                            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(604800)).build());

            // When
            String token = tokenService.createRefreshToken(testUser, TOKEN_ID);

            // Then
            assertThat(token).isEqualTo("encoded-refresh-token");

            ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
            verify(jwtEncoder).encode(captor.capture());

            JwtClaimsSet claims = captor.getValue().getClaims();
            assertThat((String) claims.getClaim(TokenConstants.CLAIM_TOKEN_TYPE)).isEqualTo(TokenConstants.TOKEN_TYPE_REFRESH);
            long durationSec = claims.getExpiresAt().getEpochSecond() - claims.getIssuedAt().getEpochSecond();
            assertThat(durationSec).isEqualTo(604800L);
        }

        @Test
        @DisplayName("should share same tokenId with access token (for rotation detection)")
        void shouldShareTokenIdWithAccess() {
            // Given
            when(jwtProperties.getRefreshTokenDurationSeconds()).thenReturn(604800L);
            when(jwtProperties.getIssuer()).thenReturn("spring-ai");
            when(jwtEncoder.encode(any())).thenReturn(
                    Jwt.withTokenValue("t").header("alg", "RS256").subject(EMAIL)
                            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(604800)).build());

            // When
            tokenService.createRefreshToken(testUser, TOKEN_ID);

            // Then
            ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
            verify(jwtEncoder).encode(captor.capture());
            assertThat(captor.getValue().getClaims().getId()).isEqualTo(TOKEN_ID);
        }

        @Test
        @DisplayName("ADMIN user — should have role=ADMIN in claims")
        void adminUser_ShouldHaveAdminRole() {
            testUser.setRole(UserRole.ADMIN);
            when(jwtProperties.getRefreshTokenDurationSeconds()).thenReturn(604800L);
            when(jwtProperties.getIssuer()).thenReturn("spring-ai");
            when(jwtEncoder.encode(any())).thenReturn(
                    Jwt.withTokenValue("t").header("alg", "RS256").subject(EMAIL)
                            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(604800)).build());

            tokenService.createRefreshToken(testUser, TOKEN_ID);

            ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
            verify(jwtEncoder).encode(captor.capture());
            assertThat((String) captor.getValue().getClaims().getClaim(TokenConstants.CLAIM_ROLE)).isEqualTo("ADMIN");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // validateToken
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("validateToken")
    class ValidateTokenTests {

        @Test
        @DisplayName("valid token — should decode and return Jwt")
        void validToken_ShouldDecode() {
            Jwt expected = Jwt.withTokenValue("valid-jwt")
                    .header("alg", "RS256").subject(EMAIL)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900))
                    .build();
            when(jwtDecoder.decode("valid-jwt")).thenReturn(expected);

            Jwt result = tokenService.validateToken("valid-jwt");

            assertThat(result.getSubject()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("invalid/expired token — should throw JwtException")
        void invalidToken_ShouldThrow() {
            when(jwtDecoder.decode("bad-jwt")).thenThrow(new JwtException("Token expired"));

            assertThatThrownBy(() -> tokenService.validateToken("bad-jwt"))
                    .isInstanceOf(JwtException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RS256 algorithm verification
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("should use RS256 algorithm in JWS header (SECURITY: not HS256)")
    void shouldUseRs256Algorithm() {
        when(jwtProperties.getAccessTokenDurationSeconds()).thenReturn(900L);
        when(jwtProperties.getIssuer()).thenReturn("spring-ai");
        when(jwtEncoder.encode(any())).thenReturn(
                Jwt.withTokenValue("t").header("alg", "RS256").subject(EMAIL)
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());

        tokenService.createAccessToken(testUser, TOKEN_ID);

        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(captor.capture());

        JwsHeader header = captor.getValue().getJwsHeader();
        assertThat(header.getAlgorithm().getName()).isEqualTo("RS256");
    }
}
