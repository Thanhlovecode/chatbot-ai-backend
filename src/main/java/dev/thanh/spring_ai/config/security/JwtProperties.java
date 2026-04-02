package dev.thanh.spring_ai.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Centralized JWT configuration properties.
 * All JWT-related settings are managed through this single class.
 */
@ConfigurationProperties(prefix = "security.jwt")
@Getter
@Setter
public class JwtProperties {
    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private long accessTokenDurationSeconds = 900;       // 15 minutes
    private long refreshTokenDurationSeconds = 604800;    // 7 days
    private String issuer = "spring-ai";
}
