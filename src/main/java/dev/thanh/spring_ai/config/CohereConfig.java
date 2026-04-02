package dev.thanh.spring_ai.config;

import com.cohere.api.CohereApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CohereConfig {

    @Value("${cohere.api-key}")
    private String cohereApiKey;

    @Bean
    public CohereApiClient cohereApiClient() {
        return CohereApiClient.builder()
                .token(cohereApiKey)
                .build();
    }
}
