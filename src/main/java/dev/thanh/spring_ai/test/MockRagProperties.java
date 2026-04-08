package dev.thanh.spring_ai.test;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Mock RAG behavior during load testing.
 * <p>
 * Only relevant when running with profile 'test' and rag.mock.enabled=true.
 * Simulates the latency of: Gemini Embedding API + Qdrant gRPC search + Cohere Rerank API.
 */
@Component
@ConfigurationProperties(prefix = "rag.mock")
@Getter
@Setter
public class MockRagProperties {

    /**
     * Enable mock RAG service (disables real Embedding + Qdrant + Rerank calls).
     */
    private boolean enabled = false;

    /**
     * Minimum simulated latency in milliseconds.
     * Real RAG pipeline typically takes 100-200ms minimum.
     */
    private int minDelayMs = 100;

    /**
     * Maximum simulated latency in milliseconds.
     * Real RAG pipeline can take up to 500-750ms under load.
     */
    private int maxDelayMs = 500;

    /**
     * Simulated failure rate (0.0 = never, 1.0 = always).
     * Recommended: 0.01 (1%) — RAG failures are less common than LLM failures.
     */
    private double failureRate = 0.01;
}
