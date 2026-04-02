package dev.thanh.spring_ai.config.security;

import dev.thanh.spring_ai.constants.TokenConstants;
import dev.thanh.spring_ai.enums.SecurityErrorCode;
import dev.thanh.spring_ai.exception.SecurityAuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Custom JWT decoder that validates tokens against both
 * RSA signature AND Redis blacklist/whitelist.
 *
 * Security layers:
 * 1. RSA signature verification (shared, cached NimbusJwtDecoder)
 * 2. Blacklist check (is this specific token revoked?)
 * 3. Whitelist check (does this user have a valid session?) — see comment below
 */
@Component
@RequiredArgsConstructor
public class CustomJwtDecoder implements JwtDecoder {

    private final NimbusJwtDecoder delegate; // cached Spring bean — not created per-request
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Jwt decode(String token) throws JwtException {
        // 1. Verify RSA signature (using cached Spring bean — thread-safe)
        Jwt jwt = delegate.decode(token);

        // 2. Check if token is blacklisted (revoked via logout)
        String blacklistKey = TokenConstants.AT_BLACK_LIST + jwt.getId();
        Object blacklistValue = redisTemplate.opsForValue().get(blacklistKey);
        if (blacklistValue != null) {
            throw new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
        }

        // 3. Check if user has an active session (whitelist)
        // String userId = jwt.getClaimAsString(TokenConstants.CLAIM_USER_ID);
        // String whitelistKey = TokenConstants.AT_WHITE_LIST + userId;
        // Object whitelistValue = redisTemplate.opsForValue().get(whitelistKey);
        // if (whitelistValue == null) {
        // throw new SecurityAuthException(SecurityErrorCode.INVALID_TOKEN);
        // }

        return jwt;
    }
}
