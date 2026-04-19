package dev.thanh.spring_ai.service.security;

import dev.thanh.spring_ai.config.security.JwtProperties;
import dev.thanh.spring_ai.constants.TokenConstants;
import dev.thanh.spring_ai.dto.request.GoogleLoginRequest;
import dev.thanh.spring_ai.dto.response.GoogleUserInfo;
import dev.thanh.spring_ai.entity.User;
import dev.thanh.spring_ai.enums.SecurityErrorCode;
import dev.thanh.spring_ai.enums.UserRole;
import dev.thanh.spring_ai.exception.SecurityAuthException;
import dev.thanh.spring_ai.repository.UserRepository;
import dev.thanh.spring_ai.service.security.AuthenticationService.TokenPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthenticationService unit tests — covers key security flows:
 * 1. Google OAuth login (new user + existing user)
 * 2. Token refresh with rotation detection (replay attack)
 * 3. Logout (access + refresh token blacklisting)
 * 4. Token confusion attack prevention (ACCESS token → refresh endpoint)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService — Unit Tests (Security)")
class AuthenticationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private TokenService tokenService;
    @Mock private GoogleTokenVerifierService googleTokenVerifierService;
    @Mock private JwtProperties jwtProperties;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private AuthenticationService authenticationService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "test@gmail.com";
    private static final String GOOGLE_ID = "google-123";

    private User testUser;
    private GoogleUserInfo googleUserInfo;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(USER_ID)
                .email(EMAIL)
                .displayName("Test User")
                .googleId(GOOGLE_ID)
                .avatarUrl("https://google.com/photo.jpg")
                .role(UserRole.USER)
                .active(true)
                .build();

        googleUserInfo = GoogleUserInfo.builder()
                .email(EMAIL)
                .name("Test User")
                .googleId(GOOGLE_ID)
                .picture("https://google.com/photo.jpg")
                .emailVerified(true)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // authenticateWithGoogle
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("authenticateWithGoogle")
    class AuthenticateWithGoogleTests {

        @Test
        @DisplayName("new user — should register and return token pair")
        void newUser_ShouldRegisterAndReturnTokenPair() {
            // Given
            when(googleTokenVerifierService.verifyAndExtract("valid-id-token"))
                    .thenReturn(googleUserInfo);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(USER_ID);
                return u;
            });
            stubTokenGeneration();

            // When
            TokenPair result = authenticationService.authenticateWithGoogle(
                    new GoogleLoginRequest("valid-id-token"));

            // Then
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-token");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getEmail()).isEqualTo(EMAIL);
            assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
            assertThat(savedUser.isActive()).isTrue();
            assertThat(savedUser.getGoogleId()).isEqualTo(GOOGLE_ID);
        }

        @Test
        @DisplayName("existing user — should return token pair without creating new user")
        void existingUser_ShouldReturnTokenPairWithoutCreatingNewUser() {
            // Given
            when(googleTokenVerifierService.verifyAndExtract("valid-id-token"))
                    .thenReturn(googleUserInfo);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testUser));
            stubTokenGeneration();

            // When
            TokenPair result = authenticationService.authenticateWithGoogle(
                    new GoogleLoginRequest("valid-id-token"));

            // Then
            assertThat(result.accessToken()).isEqualTo("access-token");
            // userRepository.save should NOT be called for user creation
            // (may be called for googleId/avatar update)
        }

        @Test
        @DisplayName("existing user without googleId — should link Google account and update")
        void existingUserNoGoogleId_ShouldLinkGoogleAccount() {
            // Given: user exists but without googleId
            testUser.setGoogleId(null);
            when(googleTokenVerifierService.verifyAndExtract("valid-id-token"))
                    .thenReturn(googleUserInfo);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);
            stubTokenGeneration();

            // When
            authenticationService.authenticateWithGoogle(new GoogleLoginRequest("valid-id-token"));

            // Then: should save with updated googleId
            verify(userRepository).save(argThat(user ->
                    GOOGLE_ID.equals(user.getGoogleId())));
        }

        @Test
        @DisplayName("existing user with new avatar — should update avatar")
        void existingUserNewAvatar_ShouldUpdateAvatar() {
            // Given: user exists but avatar changed
            testUser.setAvatarUrl("https://old-avatar.jpg");
            GoogleUserInfo newInfo = GoogleUserInfo.builder()
                    .email(EMAIL).name("Test User").googleId(GOOGLE_ID)
                    .picture("https://new-avatar.jpg").emailVerified(true).build();
            when(googleTokenVerifierService.verifyAndExtract("valid-id-token"))
                    .thenReturn(newInfo);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);
            stubTokenGeneration();

            // When
            authenticationService.authenticateWithGoogle(new GoogleLoginRequest("valid-id-token"));

            // Then: should update avatar
            verify(userRepository).save(argThat(user ->
                    "https://new-avatar.jpg".equals(user.getAvatarUrl())));
        }

        @Test
        @DisplayName("when Google token invalid — should propagate SecurityAuthException")
        void invalidGoogleToken_ShouldThrow() {
            when(googleTokenVerifierService.verifyAndExtract("bad-token"))
                    .thenThrow(new SecurityAuthException(SecurityErrorCode.GOOGLE_TOKEN_INVALID));

            assertThatThrownBy(() -> authenticationService.authenticateWithGoogle(
                    new GoogleLoginRequest("bad-token")))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.GOOGLE_TOKEN_INVALID));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // refreshToken
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("refreshToken — rotation detection")
    class RefreshTokenTests {

        @Test
        @DisplayName("valid refresh token — should return new token pair")
        void validRefreshToken_ShouldReturnNewPair() {
            // Given
            String tokenId = UUID.randomUUID().toString();
            Jwt jwt = buildJwt(tokenId, TokenConstants.TOKEN_TYPE_REFRESH);
            when(tokenService.validateToken("refresh-jwt")).thenReturn(jwt);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(TokenConstants.RT_WHITE_LIST + USER_ID)).thenReturn(tokenId);
            when(valueOps.get(TokenConstants.RT_BLACK_LIST + tokenId)).thenReturn(null);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testUser));
            stubTokenGeneration();

            // When
            TokenPair result = authenticationService.refreshToken("refresh-jwt");

            // Then
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-token");
        }

        @Test
        @DisplayName("🔴 TOKEN CONFUSION ATTACK — ACCESS token used for refresh — should reject")
        void tokenConfusionAttack_AccessTokenForRefresh_ShouldReject() {
            // Given: attacker sends a stolen ACCESS token to the refresh endpoint
            String tokenId = UUID.randomUUID().toString();
            Jwt accessJwt = buildJwt(tokenId, TokenConstants.TOKEN_TYPE_ACCESS);
            when(tokenService.validateToken("stolen-access-token")).thenReturn(accessJwt);

            // When / Then: should throw INVALID_TOKEN (prevents extending attacker access from 15min → 7days)
            assertThatThrownBy(() -> authenticationService.refreshToken("stolen-access-token"))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.INVALID_TOKEN));
        }

        @Test
        @DisplayName("🔴 REPLAY ATTACK — old refresh token (tokenId mismatch) — should invalidate ALL tokens")
        void replayAttack_TokenIdMismatch_ShouldInvalidateAll() {
            // Given: attacker replays an old refresh token with a different tokenId
            String oldTokenId = "old-token-id";
            String currentTokenId = "current-token-id";
            Jwt jwt = buildJwt(oldTokenId, TokenConstants.TOKEN_TYPE_REFRESH);
            when(tokenService.validateToken("old-refresh-jwt")).thenReturn(jwt);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(TokenConstants.RT_WHITE_LIST + USER_ID)).thenReturn(currentTokenId);
            when(valueOps.get(TokenConstants.RT_BLACK_LIST + oldTokenId)).thenReturn(null);

            // When / Then
            assertThatThrownBy(() -> authenticationService.refreshToken("old-refresh-jwt"))
                    .isInstanceOf(SecurityAuthException.class);

            // Verify: ALL tokens for the user are invalidated (nuclear option)
            verify(redisTemplate).delete(TokenConstants.AT_WHITE_LIST + USER_ID);
            verify(redisTemplate).delete(TokenConstants.RT_WHITE_LIST + USER_ID);
        }

        @Test
        @DisplayName("🔴 REPLAY ATTACK — blacklisted refresh token — should invalidate ALL tokens")
        void replayAttack_BlacklistedToken_ShouldInvalidateAll() {
            // Given: refresh token is already blacklisted (used after logout)
            String tokenId = UUID.randomUUID().toString();
            Jwt jwt = buildJwt(tokenId, TokenConstants.TOKEN_TYPE_REFRESH);
            when(tokenService.validateToken("blacklisted-jwt")).thenReturn(jwt);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(TokenConstants.RT_WHITE_LIST + USER_ID)).thenReturn(tokenId);
            when(valueOps.get(TokenConstants.RT_BLACK_LIST + tokenId)).thenReturn(TokenConstants.VALID_STATUS);

            // When / Then
            assertThatThrownBy(() -> authenticationService.refreshToken("blacklisted-jwt"))
                    .isInstanceOf(SecurityAuthException.class);

            // Nuclear invalidation triggered
            verify(redisTemplate).delete(TokenConstants.AT_WHITE_LIST + USER_ID);
            verify(redisTemplate).delete(TokenConstants.RT_WHITE_LIST + USER_ID);
        }

        @Test
        @DisplayName("user not found after valid refresh — should throw INVALID_TOKEN")
        void userNotFound_ShouldThrow() {
            String tokenId = UUID.randomUUID().toString();
            Jwt jwt = buildJwt(tokenId, TokenConstants.TOKEN_TYPE_REFRESH);
            when(tokenService.validateToken("refresh-jwt")).thenReturn(jwt);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(TokenConstants.RT_WHITE_LIST + USER_ID)).thenReturn(tokenId);
            when(valueOps.get(TokenConstants.RT_BLACK_LIST + tokenId)).thenReturn(null);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authenticationService.refreshToken("refresh-jwt"))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.INVALID_TOKEN));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // logout
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("logout — token blacklisting")
    class LogoutTests {

        @Test
        @DisplayName("valid Bearer token — should blacklist both access and refresh tokens")
        void validBearerToken_ShouldBlacklistBoth() {
            // Given
            String tokenId = UUID.randomUUID().toString();
            Jwt jwt = Jwt.withTokenValue("access-jwt")
                    .header("alg", "RS256")
                    .claim(TokenConstants.CLAIM_USER_ID, USER_ID.toString())
                    .subject(EMAIL)
                    .claim("jti", tokenId)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(600)) // 10 min left
                    .build();
            when(tokenService.validateToken("access-jwt")).thenReturn(jwt);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(jwtProperties.getRefreshTokenDurationSeconds()).thenReturn(604800L);

            // When
            authenticationService.logout("Bearer access-jwt");

            // Then: access token blacklisted with remaining TTL
            verify(valueOps).set(
                    eq(TokenConstants.AT_BLACK_LIST + tokenId),
                    eq(TokenConstants.VALID_STATUS),
                    longThat(ttl -> ttl > 0 && ttl <= 600),
                    eq(TimeUnit.SECONDS));

            // Then: refresh token blacklisted with full refresh duration
            verify(valueOps).set(
                    eq(TokenConstants.RT_BLACK_LIST + tokenId),
                    eq(TokenConstants.VALID_STATUS),
                    eq(604800L),
                    eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("null auth header — should throw INVALID_TOKEN")
        void nullAuthHeader_ShouldThrow() {
            assertThatThrownBy(() -> authenticationService.logout(null))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.INVALID_TOKEN));
        }

        @Test
        @DisplayName("auth header without Bearer prefix — should throw INVALID_TOKEN")
        void noBearerPrefix_ShouldThrow() {
            assertThatThrownBy(() -> authenticationService.logout("Basic abc123"))
                    .isInstanceOf(SecurityAuthException.class);
        }

        @Test
        @DisplayName("already expired token — should NOT blacklist access (TTL <= 0)")
        void expiredToken_ShouldNotBlacklistAccess() {
            String tokenId = UUID.randomUUID().toString();
            Jwt jwt = Jwt.withTokenValue("expired-jwt")
                    .header("alg", "RS256")
                    .claim(TokenConstants.CLAIM_USER_ID, USER_ID.toString())
                    .subject(EMAIL)
                    .claim("jti", tokenId)
                    .issuedAt(Instant.now().minusSeconds(1000))
                    .expiresAt(Instant.now().minusSeconds(10)) // already expired
                    .build();
            when(tokenService.validateToken("expired-jwt")).thenReturn(jwt);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(jwtProperties.getRefreshTokenDurationSeconds()).thenReturn(604800L);

            authenticationService.logout("Bearer expired-jwt");

            // Access token blacklist should NOT be called (TTL <= 0)
            verify(valueOps, never()).set(
                    startsWith(TokenConstants.AT_BLACK_LIST),
                    eq(TokenConstants.VALID_STATUS),
                    longThat(ttl -> ttl > 0),
                    eq(TimeUnit.SECONDS));

            // Refresh token should still be blacklisted
            verify(valueOps).set(
                    eq(TokenConstants.RT_BLACK_LIST + tokenId),
                    eq(TokenConstants.VALID_STATUS),
                    eq(604800L),
                    eq(TimeUnit.SECONDS));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // generatePairToken — whitelist management
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("generatePairToken — whitelist")
    class GeneratePairTokenTests {

        @Test
        @DisplayName("should save access + refresh whitelist with correct TTLs")
        void shouldSaveWhitelistWithCorrectTtls() {
            // Given
            stubTokenGeneration();

            // When
            TokenPair result = authenticationService.generatePairToken(testUser);

            // Then
            assertThat(result.accessToken()).isNotNull();
            assertThat(result.refreshToken()).isNotNull();

            // Verify access token whitelist with access TTL
            verify(valueOps).set(
                    eq(TokenConstants.AT_WHITE_LIST + USER_ID),
                    eq(TokenConstants.VALID_STATUS),
                    eq(900L),
                    eq(TimeUnit.SECONDS));

            // Verify refresh token whitelist with refresh TTL and stores tokenId
            verify(valueOps).set(
                    eq(TokenConstants.RT_WHITE_LIST + USER_ID),
                    anyString(), // tokenId (UUID)
                    eq(604800L),
                    eq(TimeUnit.SECONDS));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void stubTokenGeneration() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(jwtProperties.getAccessTokenDurationSeconds()).thenReturn(900L);
        lenient().when(jwtProperties.getRefreshTokenDurationSeconds()).thenReturn(604800L);
        lenient().when(tokenService.createAccessToken(any(User.class), anyString()))
                .thenReturn("access-token");
        lenient().when(tokenService.createRefreshToken(any(User.class), anyString()))
                .thenReturn("refresh-token");
    }

    private Jwt buildJwt(String tokenId, String tokenType) {
        return Jwt.withTokenValue("jwt-value")
                .header("alg", "RS256")
                .claim(TokenConstants.CLAIM_USER_ID, USER_ID.toString())
                .claim(TokenConstants.CLAIM_TOKEN_TYPE, tokenType)
                .claim(TokenConstants.CLAIM_ROLE, "USER")
                .subject(EMAIL)
                .claim("jti", tokenId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
