package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.tools.JavaKnowledgeTools;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

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

    @Mock
    private JavaKnowledgeTools knowledgeTool;

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
        llmService = new LlmService(chatClient, defaultRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry, knowledgeTool);
    }

    // ─────────────────────────────────────────────────────────
    // Helper: mock ChatClient fluent chain for streamResponse
    // ─────────────────────────────────────────────────────────

    private void mockStreamResponse(Flux<String> flux) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec systemSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec messagesSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec toolsSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system((org.springframework.core.io.Resource) any())).thenReturn(systemSpec);
        when(systemSpec.messages(any(List.class))).thenReturn(messagesSpec);
        when(messagesSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.tools(any())).thenReturn(toolsSpec);
        when(toolsSpec.stream()).thenReturn(streamSpec);
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
        StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList()))
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
                        llmService.streamResponse("query", Collections.emptyList()))
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
        StepVerifier.create(llmService.streamResponse("question", Collections.emptyList()))
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
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry, knowledgeTool);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");

        // Manually transition to OPEN
        cb.transitionToOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Note: CircuitBreakerOperator intercepts at subscribe time — Reactor still builds the chain eagerly,
        // so we need to mock the ChatClient chain. We use Flux.never() as the upstream source;
        // the CB will reject subscription before any item is emitted. 
        mockStreamResponse(Flux.never());

        // When & Then: CallNotPermittedException → fail-fast message (no timeout wait)
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("question", Collections.emptyList()))
                .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                .verifyComplete();
    }

    @Test
    @DisplayName("streamResponse — after enough failures — CB should OPEN and subsequent calls fail-fast")
    void streamResponse_AfterFailures_CircuitShouldOpen_AndSubsequentCallsFailFast() {
        // Given: sensitive registry (1 failure = OPEN)
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry, knowledgeTool);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");

        // First call: mock Gemini error — this triggers CB to OPEN (1 failure = 100% rate)
        mockStreamResponse(Flux.error(new RuntimeException("Gemini 503 Service Unavailable")));
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("first", Collections.emptyList()))
                .assertNext(msg -> assertThat(msg).containsAnyOf("lỗi", "Xin lỗi"))
                .verifyComplete();

        // CB should now be OPEN after 1 failure
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Second call: CB OPEN → fail-fast without calling Gemini at all
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("second", Collections.emptyList()))
                .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                .verifyComplete();
    }

    @Test
    @DisplayName("generateTitle — when CB is OPEN — should return Mono.empty (silent fallback)")
    void generateTitle_WhenCircuitBreakerOpen_ShouldReturnEmpty() {
        // Given: force CB into OPEN state
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry, knowledgeTool);
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

    // ═══════════════════════════════════════════════════════════════════════
    // isSafeToRetry — Exhaustive Whitelist/Blacklist Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isSafeToRetry — Exception Whitelist/Blacklist")
    class IsSafeToRetryTests {

        /**
         * Uses reflection to test private isSafeToRetry method directly.
         * This avoids complex Flux setup for each exception type.
         */
        private boolean invokeIsSafeToRetry(Throwable error, boolean hasEmitted) {
            try {
                var method = LlmService.class.getDeclaredMethod("isSafeToRetry", Throwable.class, boolean.class);
                method.setAccessible(true);
                return (boolean) method.invoke(llmService, error, hasEmitted);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke isSafeToRetry", e);
            }
        }

        // ─── BLACKLIST: Should NOT retry ─────────────────────────
        @Test
        @DisplayName("when hasEmitted=true — should always return false (no retry after data sent)")
        void hasEmitted_ShouldNeverRetry() {
            // Even retryable exceptions should be blocked after data emission
            assertThat(invokeIsSafeToRetry(new TimeoutException("idle"), true)).isFalse();
            assertThat(invokeIsSafeToRetry(new IOException("network"), true)).isFalse();
            assertThat(invokeIsSafeToRetry(new ConnectException("refused"), true)).isFalse();
        }

        @Test
        @DisplayName("BulkheadFullException — should NOT retry (internal protection)")
        void bulkheadFull_ShouldNotRetry() {
            BulkheadFullException ex = BulkheadFullException.createBulkheadFullException(
                    io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test"));
            assertThat(invokeIsSafeToRetry(ex, false)).isFalse();
        }

        @Test
        @DisplayName("RequestNotPermitted — should NOT retry (RateLimiter rejection)")
        void requestNotPermitted_ShouldNotRetry() {
            RequestNotPermitted ex = RequestNotPermitted.createRequestNotPermitted(
                    io.github.resilience4j.ratelimiter.RateLimiter.ofDefaults("test"));
            assertThat(invokeIsSafeToRetry(ex, false)).isFalse();
        }

        @Test
        @DisplayName("CallNotPermittedException — should NOT retry (CB OPEN rejection)")
        void callNotPermitted_ShouldNotRetry() {
            CircuitBreaker cb = CircuitBreaker.ofDefaults("test");
            cb.transitionToOpenState();
            CallNotPermittedException ex = CallNotPermittedException.createCallNotPermittedException(cb);
            assertThat(invokeIsSafeToRetry(ex, false)).isFalse();
        }

        @Test
        @DisplayName("RuntimeException — should NOT retry (not in whitelist)")
        void runtimeException_ShouldNotRetry() {
            assertThat(invokeIsSafeToRetry(new RuntimeException("random error"), false)).isFalse();
        }

        @Test
        @DisplayName("NullPointerException — should NOT retry (application bug)")
        void nullPointerException_ShouldNotRetry() {
            assertThat(invokeIsSafeToRetry(new NullPointerException(), false)).isFalse();
        }

        @Test
        @DisplayName("IllegalArgumentException — should NOT retry")
        void illegalArgument_ShouldNotRetry() {
            assertThat(invokeIsSafeToRetry(new IllegalArgumentException("bad input"), false)).isFalse();
        }

        // ─── WHITELIST: Should retry ─────────────────────────────
        @Test
        @DisplayName("TimeoutException — should retry (Gemini idle timeout)")
        void timeoutException_ShouldRetry() {
            assertThat(invokeIsSafeToRetry(new TimeoutException("Gemini idle > 30s"), false)).isTrue();
        }

        @Test
        @DisplayName("IOException — should retry (network failure)")
        void ioException_ShouldRetry() {
            assertThat(invokeIsSafeToRetry(new IOException("Connection reset"), false)).isTrue();
        }

        @Test
        @DisplayName("ConnectException — should retry (TCP connection refused)")
        void connectException_ShouldRetry() {
            assertThat(invokeIsSafeToRetry(new ConnectException("Connection refused"), false)).isTrue();
        }

        // ─── NESTED EXCEPTION: Root cause extraction ─────────────
        @Test
        @DisplayName("wrapped TimeoutException — should unwrap and retry")
        void wrappedTimeout_ShouldUnwrapAndRetry() {
            Exception wrapped = new RuntimeException("Spring wrapper",
                    new TimeoutException("Gemini timeout"));
            assertThat(invokeIsSafeToRetry(wrapped, false)).isTrue();
        }

        @Test
        @DisplayName("wrapped IOException — should unwrap and retry")
        void wrappedIOException_ShouldUnwrapAndRetry() {
            Exception wrapped = new RuntimeException("Spring wrapper",
                    new IOException("Stream closed"));
            assertThat(invokeIsSafeToRetry(wrapped, false)).isTrue();
        }

        @Test
        @DisplayName("deeply nested ConnectException — should find root cause and retry")
        void deeplyNested_ShouldFindRootCause() {
            Exception deep = new RuntimeException("level1",
                    new RuntimeException("level2",
                            new ConnectException("Connection refused")));
            assertThat(invokeIsSafeToRetry(deep, false)).isTrue();
        }

        @Test
        @DisplayName("wrapped non-retryable RuntimeException — should NOT retry")
        void wrappedNonRetryable_ShouldNotRetry() {
            Exception wrapped = new RuntimeException("Spring wrapper",
                    new IllegalStateException("some state error"));
            assertThat(invokeIsSafeToRetry(wrapped, false)).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mid-stream interruption — "đã emit data lên client"
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Mid-stream interruption")
    class MidStreamInterruptionTests {

        @Test
        @DisplayName("when stream emits tokens then errors — should emit error suffix, no retry text repeat")
        void midStreamError_ShouldEmitErrorSuffix() {
            // Given: emit 2 tokens, then error — simulates Gemini stream cutting mid-response
            mockStreamResponse(Flux.concat(
                    Flux.just("Hello", " World"),
                    Flux.error(new IOException("Connection reset mid-stream"))
            ));

            // When & Then
            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList()))
                    .expectNext("Hello")
                    .expectNext(" World")
                    // After emitting data, the error is caught and a suffix is appended
                    .assertNext(msg -> assertThat(msg).contains("gián đoạn"))
                    .verifyComplete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RateLimiter rejection — different from CB
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RateLimiter rejection")
    class RateLimiterRejectionTests {

        @Test
        @DisplayName("when RateLimiter rejects — should emit quota exhausted message (not throw)")
        void whenRateLimiterRejects_ShouldEmitFriendlyMessage() {
            // Create RL with 1 permit and 60s refresh: after 1 call the next will be rejected
            RateLimiterRegistry strictRL = RateLimiterRegistry.of(
                    RateLimiterConfig.custom()
                            .limitForPeriod(1)
                            .limitRefreshPeriod(Duration.ofSeconds(60))
                            .timeoutDuration(Duration.ZERO) // fail immediately
                            .build()
            );
            LlmService strictService = new LlmService(chatClient, defaultRegistry, strictRL, bulkheadRegistry, meterRegistry, knowledgeTool);

            // Exhaust the single permit
            io.github.resilience4j.ratelimiter.RateLimiter rl = strictRL.rateLimiter("llm-gemini");
            rl.acquirePermission(); // consume the only permit

            mockStreamResponse(Flux.just("should-not-reach"));

            StepVerifier.create(strictService.streamResponse("Hi", Collections.emptyList()))
                    .assertNext(msg -> assertThat(msg).contains("quá tải"))
                    .verifyComplete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // handleFinalTerminalError — error classification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleFinalTerminalError — error classification")
    class ErrorClassificationTests {

        @Test
        @DisplayName("when CB OPEN — should return 'tạm thời không khả dụng'")
        void cbOpen_ShouldReturnUnavailableMessage() {
            LlmService service = new LlmService(chatClient, sensitiveRegistry, rateLimiterRegistry, bulkheadRegistry, meterRegistry, knowledgeTool);
            sensitiveRegistry.circuitBreaker("llm-gemini").transitionToOpenState();
            mockStreamResponse(Flux.never());

            StepVerifier.create(service.streamResponse("q", Collections.emptyList()))
                    .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("when generic error — should include error message in response")
        void genericError_ShouldIncludeMessage() {
            mockStreamResponse(Flux.error(new RuntimeException("Unexpected API error")));

            StepVerifier.create(llmService.streamResponse("q", Collections.emptyList()))
                    .assertNext(msg -> assertThat(msg).contains("Unexpected API error"))
                    .verifyComplete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Metrics verification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Prometheus metrics")
    class MetricsTests {

        @Test
        @DisplayName("successful stream — should increment success counter")
        void successfulStream_ShouldIncrementSuccessCounter() {
            mockStreamResponse(Flux.just("token1", "token2"));

            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList()))
                    .expectNextCount(2)
                    .verifyComplete();

            var counter = meterRegistry.find("llm.stream.status").tag("result", "success").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("TTFB timer — should record on first token")
        void ttfbTimer_ShouldRecordOnFirstToken() {
            mockStreamResponse(Flux.just("first-token"));

            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList()))
                    .expectNext("first-token")
                    .verifyComplete();

            var timer = meterRegistry.find("llm.stream.ttfb").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isGreaterThanOrEqualTo(1);
        }
    }
}
