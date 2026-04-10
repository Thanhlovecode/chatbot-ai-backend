package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.dto.request.ChatMessageRequest;
import dev.thanh.spring_ai.dto.response.ChatResponse;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.enums.ResponseType;
import dev.thanh.spring_ai.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BLOCKER 3 Fix: ChatService unit tests using StepVerifier for Flux verification.
 * ChatService is the highest-risk component orchestrating Gemini, RAG, Rate Limiting, Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService — Unit Tests (StepVerifier)")
class ChatServiceTest {

    @Mock private RedisStreamService redisStreamService;
    @Mock private LlmService llmService;
    @Mock private UuidV7Generator uuidV7Generator;
    @Mock private ChatSessionService chatSessionService;
    @Mock private SessionActivityService sessionActivityService;
    @Mock private TokenCounterService tokenCounterService;
    @Mock private RateLimitService rateLimitService;
    @Mock private ChatMetricsService chatMetrics;

    @InjectMocks
    private ChatService chatService;

    private static final String USER_ID = "user-111";
    private static final String SESSION_ID = "session-222";
    private static final String MODEL_NAME = "gemini-2.5-flash";

    @BeforeEach
    void setUp() {
        // Inject virtual thread executor for async tasks
        ReflectionTestUtils.setField(chatService, "virtualThreadExecutor",
                Executors.newVirtualThreadPerTaskExecutor());
        ReflectionTestUtils.setField(chatService, "modelName", MODEL_NAME);
        // Default behaviors
        lenient().when(uuidV7Generator.generate()).thenReturn(UUID.randomUUID());

        // ChatMetricsService stubs — mock returns for getter methods used in chat pipeline
        lenient().when(chatMetrics.getActiveStreams()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(0));
        lenient().when(chatMetrics.startStreamTimer()).thenReturn(io.micrometer.core.instrument.Timer.start());
    }

    // ─────────────────────────────────────────────────────────
    // Rate Limiting — Layer 1
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("chatStream — when token bucket exceeded (Layer 1) — should re-throw RateLimitException")
    void chatStream_WhenRateLimitExceeded_ShouldReThrowRateLimitException() {
        // Given
        doThrow(new RateLimitException(RateLimitErrorCode.TOO_MANY_REQUESTS, 10L))
                .when(rateLimitService).checkTokenBucket(USER_ID);
        ChatMessageRequest request = new ChatMessageRequest("Hello", SESSION_ID);

        // When / Then — RateLimitException re-thrown (not wrapped), controller handles HTTP 429
        org.junit.jupiter.api.Assertions.assertThrows(RateLimitException.class,
                () -> chatService.chatStream(request, USER_ID));
    }

    // ─────────────────────────────────────────────────────────
    // Rate Limiting — Layer 2 (Daily quota via Flux pipeline)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("chatStream — when daily token quota exceeded (Layer 2) — should re-emit RateLimitException")
    void chatStream_WhenDailyTokenQuotaExceeded_ShouldReEmitRateLimitException() {
        // Given
        doNothing().when(rateLimitService).checkTokenBucket(USER_ID);
        when(chatSessionService.getOrCreateSession(SESSION_ID, USER_ID)).thenReturn(SESSION_ID);
        when(chatSessionService.prepareHistory(anyString(), anyBoolean())).thenReturn(List.of());
        when(tokenCounterService.countInputTokens(any(), any())).thenReturn(500);
        doThrow(new RateLimitException(RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED, 10000L, 10000L))
                .when(rateLimitService).checkDailyTokenQuota(USER_ID, 500);

        ChatMessageRequest request = new ChatMessageRequest("Hello", SESSION_ID);

        // When
        Flux<ChatResponse> result = chatService.chatStream(request, USER_ID);

        // Then — RateLimitException is re-thrown via onErrorResume for RateLimitException
        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof RateLimitException
                        && ((RateLimitException) e).getErrorCode() == RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED)
                .verify();
    }

    // ─────────────────────────────────────────────────────────
    // Happy Path — Existing Session
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("chatStream — existing session — should emit only CONTENT responses")
    void chatStream_WhenExistingSession_ShouldEmitOnlyContentResponses() {
        // Given
        doNothing().when(rateLimitService).checkTokenBucket(USER_ID);
        when(chatSessionService.getOrCreateSession(SESSION_ID, USER_ID)).thenReturn(SESSION_ID);
        when(chatSessionService.prepareHistory(anyString(), eq(false))).thenReturn(List.of());
        when(tokenCounterService.countInputTokens(any(), any())).thenReturn(100);
        doNothing().when(rateLimitService).checkDailyTokenQuota(anyString(), anyInt());
        when(llmService.streamResponse(anyString(), anyList()))
                .thenReturn(Flux.just("Hello ", "World"));
        doNothing().when(redisStreamService).pushToStream(any());

        ChatMessageRequest request = new ChatMessageRequest("Hello", SESSION_ID);

        // When
        Flux<ChatResponse> result = chatService.chatStream(request, USER_ID);

        // Then
        StepVerifier.create(result)
                .assertNext(r -> {
                    assertThat(r.getType()).isEqualTo(ResponseType.CONTENT);
                    assertThat(r.getSessionId()).isEqualTo(SESSION_ID);
                })
                .assertNext(r -> assertThat(r.getType()).isEqualTo(ResponseType.CONTENT))
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Happy Path — New Session (Content + Title streams merged)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("chatStream — new session — should emit CONTENT and TITLE responses (merged)")
    void chatStream_WhenNewSession_ShouldEmitContentAndTitleResponses() {
        // Given
        String newSessionId = "new-session-id";
        doNothing().when(rateLimitService).checkTokenBucket(USER_ID);
        when(chatSessionService.getOrCreateSession(null, USER_ID)).thenReturn(newSessionId);
        when(chatSessionService.prepareHistory(anyString(), eq(true))).thenReturn(List.of());
        when(tokenCounterService.countInputTokens(any(), any())).thenReturn(50);
        doNothing().when(rateLimitService).checkDailyTokenQuota(anyString(), anyInt());
        when(llmService.streamResponse(anyString(), anyList()))
                .thenReturn(Flux.just("Hello"));
        when(llmService.generateTitle(anyString()))
                .thenReturn(Mono.just("My First Chat Title"));
        doNothing().when(redisStreamService).pushToStream(any());

        ChatMessageRequest request = new ChatMessageRequest("Hello", null);

        // When
        Flux<ChatResponse> result = chatService.chatStream(request, USER_ID);

        // Then: expect at least 1 CONTENT and 1 TITLE
        StepVerifier.create(result.collectList())
                .assertNext(responses -> {
                    assertThat(responses).anyMatch(r -> r.getType() == ResponseType.CONTENT);
                    assertThat(responses).anyMatch(r -> r.getType() == ResponseType.TITLE);
                })
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Error Handling
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("chatStream — when LLM fails — should emit ERROR response (not propagate exception)")
    void chatStream_WhenLlmServiceFails_ShouldEmitErrorResponse() {
        // Given
        doNothing().when(rateLimitService).checkTokenBucket(USER_ID);
        when(chatSessionService.getOrCreateSession(SESSION_ID, USER_ID)).thenReturn(SESSION_ID);
        when(chatSessionService.prepareHistory(anyString(), anyBoolean())).thenReturn(List.of());
        when(tokenCounterService.countInputTokens(any(), any())).thenReturn(100);
        doNothing().when(rateLimitService).checkDailyTokenQuota(anyString(), anyInt());
        when(llmService.streamResponse(anyString(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("LLM connection failed")));
        doNothing().when(redisStreamService).pushToStream(any());

        ChatMessageRequest request = new ChatMessageRequest("Hi", SESSION_ID);

        // When
        Flux<ChatResponse> result = chatService.chatStream(request, USER_ID);

        // Then — generic errors are caught by onErrorResume and converted to ERROR type response
        StepVerifier.create(result)
                .assertNext(r -> {
                    assertThat(r.getType()).isEqualTo(ResponseType.ERROR);
                    assertThat(r.getContent()).contains("lỗi");
                })
                .verifyComplete();
    }
}
