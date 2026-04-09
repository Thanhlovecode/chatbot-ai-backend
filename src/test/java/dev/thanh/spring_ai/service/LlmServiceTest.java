package dev.thanh.spring_ai.service;


import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LlmService — covers both normal Gemini failure scenarios
 * and Circuit Breaker state transitions.
 *
 * Strategy for CB tests: create a test-only CircuitBreakerRegistry with low thresholds
 * (minimum-number-of-calls=1, failure-rate=100%) so we can open the CB with a single failure.
 * This avoids complex timing or real Resilience4j internals while still testing
 * the integration between LlmService and the CB operator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmService — Unit Tests (Gemini Failure Scenarios + Circuit Breaker)")
class LlmServiceTest {

    @Mock
    private ChatClient chatClient;

    // Default registry (used by most tests — CB always CLOSED)
    private CircuitBreakerRegistry defaultRegistry;

    // Test-only registry with aggressive thresholds to trigger OPEN easily
    private CircuitBreakerRegistry sensitiveRegistry;

    // Registries needed by LlmService constructor
    private RateLimiterRegistry rateLimiterRegistry;
    private BulkheadRegistry bulkheadRegistry;
    private MeterRegistry meterRegistry;

    private LlmService llmService;

    @BeforeEach
    void setUp() {
        defaultRegistry = CircuitBreakerRegistry.ofDefaults();
        rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
        bulkheadRegistry = BulkheadRegistry.ofDefaults();
        meterRegistry = new SimpleMeterRegistry();

        sensitiveRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(1)
                        .minimumNumberOfCalls(1)
                        .failureRateThreshold(100) // 1 failure = OPEN
                        .waitDurationInOpenState(Duration.ofSeconds(60)) // Stay OPEN during test
                        .recordExceptions(RuntimeException.class)
                        .build()
        );
        // Use default registry by default — tests needing CB OPEN will override
        llmService = new LlmService(chatClient, defaultRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry);
    }

    // ─────────────────────────────────────────────────────────
    // Helper: mock ChatClient fluent chain for streamResponse
    // ─────────────────────────────────────────────────────────

    private void mockStreamResponse(Flux<String> flux) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec systemSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec messagesSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(java.util.function.Consumer.class))).thenReturn(systemSpec);
        when(systemSpec.messages(any(List.class))).thenReturn(messagesSpec);
        when(messagesSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(flux);
    }

    // ─────────────────────────────────────────────────────────
    // Happy Path
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — happy path — should emit all tokens")
    void streamResponse_WhenSuccess_ShouldEmitTokens() {
        // Given
        mockStreamResponse(Flux.just("Hello", " World", "!"));

        // When & Then
        StepVerifier.create(llmService.streamResponse("Hi", "context", Collections.emptyList()))
                .expectNext("Hello")
                .expectNext(" World")
                .expectNext("!")
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Timeout — must trigger fallback
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — when timeout — should return fallback error message (not throw)")
    void streamResponse_WhenTimeout_ShouldReturnFallbackMessage() {
        // Given: Flux.never() — không emit gì, để production timeout 2 pha tự fire (30s phase 1)
        // Retry 2 lần × 30s + backoff → tổng ~95s. Advance virtual time 120s để an toàn.
        mockStreamResponse(Flux.never());

        // When & Then: onErrorResume should catch timeout and emit a single error message
        StepVerifier.withVirtualTime(() ->
                        llmService.streamResponse("query", "ctx", Collections.emptyList()))
                .thenAwait(Duration.ofSeconds(120))
                .assertNext(msg -> assertThat(msg).containsAnyOf("lỗi", "Xin lỗi"))
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Generic Error — 500 / connection failure
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — when Gemini returns error — should emit fallback message (not propagate)")
    void streamResponse_WhenGeminiError_ShouldReturnFallbackMessage() {
        // Given: simulate Gemini API failure
        mockStreamResponse(Flux.error(new RuntimeException("500 Internal Server Error from Gemini")));

        // When & Then: onErrorResume returns a single fallback string, no exception propagated
        StepVerifier.create(llmService.streamResponse("question", "ctx", Collections.emptyList()))
                .assertNext(msg -> {
                    assertThat(msg).containsAnyOf("lỗi", "Xin lỗi");
                })
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Circuit Breaker Tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — when CB is OPEN — should fail-fast with unavailable message (no 60s wait)")
    void streamResponse_WhenCircuitBreakerOpen_ShouldFailFast() {
        // Given: use sensitive registry, force CB into OPEN state
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");

        // Manually transition to OPEN
        cb.transitionToOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Note: CircuitBreakerOperator intercepts at subscribe time — Reactor still builds the chain eagerly,
        // so we need to mock the ChatClient chain. We use Flux.never() as the upstream source;
        // the CB will reject subscription before any item is emitted. 
        mockStreamResponse(Flux.never());

        // When & Then: CallNotPermittedException → fail-fast message (no timeout wait)
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("question", "ctx", Collections.emptyList()))
                .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                .verifyComplete();
    }

    @Test
    @DisplayName("streamResponse — after enough failures — CB should OPEN and subsequent calls fail-fast")
    void streamResponse_AfterFailures_CircuitShouldOpen_AndSubsequentCallsFailFast() {
        // Given: sensitive registry (1 failure = OPEN)
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");

        // First call: mock Gemini error — this triggers CB to OPEN (1 failure = 100% rate)
        mockStreamResponse(Flux.error(new RuntimeException("Gemini 503 Service Unavailable")));
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("first", "ctx", Collections.emptyList()))
                .assertNext(msg -> assertThat(msg).containsAnyOf("lỗi", "Xin lỗi"))
                .verifyComplete();

        // CB should now be OPEN after 1 failure
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Second call: CB OPEN → fail-fast without calling Gemini at all
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("second", "ctx", Collections.emptyList()))
                .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                .verifyComplete();
    }

    @Test
    @DisplayName("generateTitle — when CB is OPEN — should return Mono.empty (silent fallback)")
    void generateTitle_WhenCircuitBreakerOpen_ShouldReturnEmpty() {
        // Given: force CB into OPEN state
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");
        cb.transitionToOpenState();

        // When & Then: no exception propagated, silent empty (title generation is best-effort)
        StepVerifier.create(serviceWithSensitiveCB.generateTitle("What is Spring AI?"))
                .verifyComplete(); // empty Mono — title simply not generated
    }

    // ─────────────────────────────────────────────────────────
    // generateTitle — existing tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateTitle — happy path — should return trimmed title")
    void generateTitle_WhenSuccess_ShouldReturnTrimmedTitle() {
        // Given: mock the fluent chain: prompt() → options() → user() → call() → content()
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec optionsSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(optionsSpec);
        when(optionsSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("  My First Chat  ");

        // When & Then
        StepVerifier.create(llmService.generateTitle("What is Spring?"))
                .assertNext(title -> assertThat(title).isEqualTo("My First Chat"))
                .verifyComplete();
    }

    @Test
    @DisplayName("generateTitle — when Gemini fails — should return Mono.empty (silent fallback)")
    void generateTitle_WhenFails_ShouldReturnEmpty() {
        // Given: mock the fluent chain: prompt() → options() → user() → call() → content()
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec optionsSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(optionsSpec);
        when(optionsSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("Gemini 429"));

        // When & Then: onErrorResume → Mono.empty()
        StepVerifier.create(llmService.generateTitle("question"))
                .verifyComplete(); // empty, no error, no items
    }
}
