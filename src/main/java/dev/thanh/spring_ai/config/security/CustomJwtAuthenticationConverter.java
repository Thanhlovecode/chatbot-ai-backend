package dev.thanh.spring_ai.config.security;

import dev.thanh.spring_ai.constants.TokenConstants;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Converts a decoded JWT into a CustomAuthenticationToken
 * containing the user's UUID and granted authorities (roles).
 */
@Component
public class CustomJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter;

    public CustomJwtAuthenticationConverter() {
        this.jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        this.jwtGrantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        this.jwtGrantedAuthoritiesConverter.setAuthoritiesClaimName(TokenConstants.CLAIM_ROLE);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = jwtGrantedAuthoritiesConverter.convert(jwt);
        String userIdStr = jwt.getClaimAsString(TokenConstants.CLAIM_USER_ID);
        UUID userId = UUID.fromString(userIdStr);
        return new CustomAuthenticationToken(jwt, authorities, userId);
    }
}
