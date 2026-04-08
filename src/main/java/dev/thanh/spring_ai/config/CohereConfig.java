package dev.thanh.spring_ai.config;

import com.cohere.api.CohereApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
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
