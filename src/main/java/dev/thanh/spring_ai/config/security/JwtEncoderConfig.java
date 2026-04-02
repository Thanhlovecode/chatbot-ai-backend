package dev.thanh.spring_ai.config.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT Encoder/Decoder configuration using RSA key-pair from JwtProperties.
 * Both encoder and decoder are registered as Spring singletons, so they are
 * created once at startup and reused across all requests (both are thread-safe).
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@EnableConfigurationProperties({JwtProperties.class, GoogleOAuth2Properties.class})
public class JwtEncoderConfig {

    private final JwtProperties jwtProperties;

    @Bean
    public JwtEncoder jwtEncoder() {
        RSAKey rsaKey = new RSAKey.Builder(jwtProperties.getPublicKey())
                .privateKey(jwtProperties.getPrivateKey())
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Shared, cached NimbusJwtDecoder injected into both CustomJwtDecoder and TokenService.
     * Avoids parsing the RSA public key and building JWK processor on every request.
     */
    @Bean
    public NimbusJwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(jwtProperties.getPublicKey()).build();
    }
}
