package dev.thanh.spring_ai.service.security;

import dev.thanh.spring_ai.config.security.JwtProperties;
import dev.thanh.spring_ai.constants.TokenConstants;
import dev.thanh.spring_ai.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Token service for creating and validating JWT tokens.
 * Uses RSA RS256 signing with configurable durations from JwtProperties.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final NimbusJwtDecoder jwtDecoder;   // cached in JwtEncoderConfig — reused, not recreated per call

    public String createAccessToken(User user, String tokenId) {
        return generateToken(user, tokenId,
                TokenConstants.TOKEN_TYPE_ACCESS,
                jwtProperties.getAccessTokenDurationSeconds());
    }

    public String createRefreshToken(User user, String tokenId) {
        return generateToken(user, tokenId,
                TokenConstants.TOKEN_TYPE_REFRESH,
                jwtProperties.getRefreshTokenDurationSeconds());
    }

    /**
     * Validate a token by decoding it with the cached RSA public key decoder.
     * Used for refresh token validation where we need basic decode without Redis checks.
     */
    public Jwt validateToken(String token) {
        return jwtDecoder.decode(token);
    }

    private String generateToken(User user, String tokenId, String tokenType, long durationSeconds) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(user.getEmail())
                .claim(TokenConstants.CLAIM_USER_ID, user.getId().toString())
                .claim(TokenConstants.CLAIM_ROLE, user.getRole().name())
                .claim(TokenConstants.CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofSeconds(durationSeconds)))
                .id(tokenId)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }
}
