package dev.thanh.spring_ai.test;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Mock LLM behavior during load testing.
 * <p>
 * Only relevant when running with profile 'test' and llm.mock.enabled=true.
 */
@Component
@ConfigurationProperties(prefix = "llm.mock")
@Getter
@Setter
public class MockLlmProperties {

    /**
     * Enable mock LLM service (disables real Gemini API calls).
     */
    private boolean enabled = false;

    /**
     * Minimum simulated latency in milliseconds (Time To First Byte).
     */
    private int minDelayMs = 300;

    /**
     * Maximum simulated latency in milliseconds.
     */
    private int maxDelayMs = 2000;

    /**
     * Number of tokens to emit per response.
     */
    private int tokensPerResponse = 40;

    /**
     * Interval in milliseconds between emitting each token.
     */
    private int tokenIntervalMs = 30;

    /**
     * Simulated failure rate (0.0 = never, 1.0 = always).
     * Recommended: 0.02 (2%) for realistic testing.
     */
    private double failureRate = 0.02;
}
