package dev.thanh.spring_ai.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google OAuth2 configuration properties.
 */
@ConfigurationProperties(prefix = "security.oauth2.google")
@Getter
@Setter
public class GoogleOAuth2Properties {
    private String clientId;
}
