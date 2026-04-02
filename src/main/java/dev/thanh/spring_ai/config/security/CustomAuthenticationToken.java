package dev.thanh.spring_ai.config.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.UUID;

/**
 * Custom authentication token that carries UUID userId
 * extracted from JWT claims, enabling easy access throughout the app.
 */
@Getter
public class CustomAuthenticationToken extends JwtAuthenticationToken {

    private final UUID userId;

    public CustomAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities, UUID userId) {
        super(jwt, authorities);
        this.userId = userId;
    }
}
