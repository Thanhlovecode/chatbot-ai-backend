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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Authentication service handling Google OAuth2 login,
 * token refresh, and logout.
 *
 * Token management strategy:
 * - Access Token: short-lived (15 min), stored in whitelist, sent in response
 * body
 * - Refresh Token: long-lived (7 days), stored in whitelist with tokenId, sent
 * via HttpOnly Cookie
 * - Logout: blacklists both access & refresh tokens, clears cookie
 * - Refresh rotation: detects and invalidates stolen refresh tokens
 */
@Service
@Slf4j(topic = "AUTH-SERVICE")
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final GoogleTokenVerifierService googleTokenVerifierService;
    private final JwtProperties jwtProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Authenticate user via Google OAuth2.
     * If user exists, link Google account if not already linked.
     * If user doesn't exist, create a new account.
     */
    @Transactional
    public TokenPair authenticateWithGoogle(GoogleLoginRequest request) {
        log.info("[LOGIN FLOW] Authenticating via Google token...");
        GoogleUserInfo googleInfo = googleTokenVerifierService.verifyAndExtract(request.idToken());
        log.info("[LOGIN FLOW] Extracted Google info for email: {}", googleInfo.getEmail());

        return userRepository.findByEmail(googleInfo.getEmail())
                .map(user -> processExistingUser(user, googleInfo))
                .orElseGet(() -> registerNewGoogleUser(googleInfo));
    }

    /**
     * Refresh an expired access token using a valid refresh token from HttpOnly
     * Cookie.
     * Implements rotation detection: if the refresh token ID doesn't match
     * the stored one, all tokens for the user are invalidated (suspected theft).
     *
     * @param refreshTokenValue raw refresh token string extracted from cookie by
     *                          controller
     */
    public TokenPair refreshToken(String refreshTokenValue) {
        log.info("[REFRESH FLOW] Processing refresh token in AuthenticationService...");
        Jwt jwt = tokenService.validateToken(refreshTokenValue);

        // Validate token type: only REFRESH tokens are allowed here.
        // Prevents "token confusion attack" where a stolen ACCESS token is
        // used to obtain a brand-new token pair, extending attacker access
        // from 15 min → 7 days.
        String tokenType = jwt.getClaimAsString(TokenConstants.CLAIM_TOKEN_TYPE);
        if (!TokenConstants.TOKEN_TYPE_REFRESH.equals(tokenType)) {
            log.warn("[REFRESH FLOW] Refresh attempt with non-REFRESH token type: {}", tokenType);
            throw new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
        }

        String userId = jwt.getClaimAsString(TokenConstants.CLAIM_USER_ID);
        String tokenId = jwt.getId();

        validateRefreshToken(userId, tokenId);

        User user = userRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> {
                    log.warn("[REFRESH FLOW] User not found for email: {}", jwt.getSubject());
                    return new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
                });

        return generatePairToken(user);
    }

    /**
     * Logout by blacklisting both the access and refresh tokens.
     */
    public void logout(String authHeader) {
        log.info("[LOGOUT FLOW] Processing logout in AuthenticationService...");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[LOGOUT FLOW] Invalid authHeader format for logout");
            throw new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
        }
        String token = authHeader.substring(7); // Remove "Bearer "
        Jwt jwt = tokenService.validateToken(token);
        blacklistTokens(jwt);
        log.info("[LOGOUT FLOW] User logged out successfully. Access token blacklisted.");
    }

    // ==================== Private Methods ====================

    private TokenPair processExistingUser(User user, GoogleUserInfo googleInfo) {
        log.info("[LOGIN FLOW] Processing existing user: {}", user.getEmail());
        boolean updated = false;

        // Cập nhật lại googleId (nếu trước đây bị thiếu) và avatar mới nhất từ Google
        if (user.getGoogleId() == null || !user.getGoogleId().equals(googleInfo.getGoogleId())) {
            user.setGoogleId(googleInfo.getGoogleId());
            updated = true;
        }

        if (googleInfo.getPicture() != null && !googleInfo.getPicture().equals(user.getAvatarUrl())) {
            user.setAvatarUrl(googleInfo.getPicture());
            updated = true;
        }

        if (updated) {
            userRepository.save(user);
            log.info("[LOGIN FLOW] Updated user info for User ID {} from Google Login", user.getId());
        }

        return generatePairToken(user);
    }

    private TokenPair registerNewGoogleUser(GoogleUserInfo googleInfo) {
        log.info("[LOGIN FLOW] Registering new Google user: {}", googleInfo.getEmail());
        User user = User.builder()
                .email(googleInfo.getEmail())
                .displayName(googleInfo.getName())
                .avatarUrl(googleInfo.getPicture())
                .googleId(googleInfo.getGoogleId())
                .role(UserRole.USER)
                .active(true)
                .build();

        user = userRepository.save(user);
        log.info("[LOGIN FLOW] ✅ New user registered via Google: {}", user.getEmail());
        return generatePairToken(user);
    }

    /**
     * Generate a new token pair and save to Redis whitelist.
     * Returns both accessToken and refreshToken as a TokenPair.
     * The controller is responsible for:
     * - putting accessToken in the JSON response body
     * - setting refreshToken as an HttpOnly cookie
     */
    public TokenPair generatePairToken(User user) {
        String tokenId = UUID.randomUUID().toString();

        String accessToken = tokenService.createAccessToken(user, tokenId);
        String refreshToken = tokenService.createRefreshToken(user, tokenId);

        saveTokenWhiteList(String.valueOf(user.getId()), tokenId);

        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Immutable value object carrying the generated token pair.
     * accessToken → response body; refreshToken → HttpOnly Cookie.
     */
    public record TokenPair(String accessToken, String refreshToken) {
    }

    /**
     * Validate refresh token against Redis whitelist.
     * If the tokenId doesn't match or is blacklisted, invalidate all tokens
     * for this user (suspected token theft / replay attack).
     */

    private void validateRefreshToken(String userId, String tokenId) {
        String refreshKey = TokenConstants.RT_WHITE_LIST + userId;
        String accessKey = TokenConstants.AT_WHITE_LIST + userId;

        Object storedRefreshTokenId = redisTemplate.opsForValue().get(refreshKey);
        Object blacklistedRefresh = redisTemplate.opsForValue()
                .get(TokenConstants.RT_BLACK_LIST + tokenId);

        // Rotation detection: tokenId mismatch or blacklisted = suspected theft
        if (!tokenId.equals(storedRefreshTokenId) || blacklistedRefresh != null) {
            log.warn("[REFRESH FLOW] 🚨 Suspicious refresh token usage for userId {}. Invalidating all tokens.",
                    userId);
            redisTemplate.delete(accessKey);
            redisTemplate.delete(refreshKey);
            throw new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
        }

        log.info("[REFRESH FLOW] Refresh token validated successfully for userId {}", userId);
    }

    private void blacklistTokens(Jwt jwt) {
        String tokenId = jwt.getId();

        // Blacklist access token (expires when the token expires)
        long accessTtl = Duration.between(Instant.now(), jwt.getExpiresAt()).getSeconds();
        if (accessTtl > 0) {
            redisTemplate.opsForValue().set(
                    TokenConstants.AT_BLACK_LIST + tokenId,
                    TokenConstants.VALID_STATUS,
                    accessTtl, TimeUnit.SECONDS);
        }

        // Blacklist refresh token (expires after refresh duration)
        redisTemplate.opsForValue().set(
                TokenConstants.RT_BLACK_LIST + tokenId,
                TokenConstants.VALID_STATUS,
                jwtProperties.getRefreshTokenDurationSeconds(), TimeUnit.SECONDS);

        log.info("[LOGOUT FLOW] Saved access and refresh token in blacklist with tokenId: {}", tokenId);
    }

    private void saveTokenWhiteList(String userId, String tokenId) {
        // Access token whitelist: just a marker
        redisTemplate.opsForValue().set(
                TokenConstants.AT_WHITE_LIST + userId,
                TokenConstants.VALID_STATUS,
                jwtProperties.getAccessTokenDurationSeconds(), TimeUnit.SECONDS);

        // Refresh token whitelist: store tokenId for rotation detection
        redisTemplate.opsForValue().set(
                TokenConstants.RT_WHITE_LIST + userId,
                tokenId,
                jwtProperties.getRefreshTokenDurationSeconds(), TimeUnit.SECONDS);
    }
}
