package dev.thanh.spring_ai.config.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Google OAuth2 configuration.
 * Creates a GoogleIdTokenVerifier bean for server-side token verification.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class GoogleConfig {

    private final GoogleOAuth2Properties googleProperties;

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier() {
        return new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleProperties.getClientId()))
                .setIssuer("https://accounts.google.com")
                .build();
    }
}
