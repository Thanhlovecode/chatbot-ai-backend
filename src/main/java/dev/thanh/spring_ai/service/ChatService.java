package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.dto.request.ChatMessageRequest;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.dto.response.ChatResponse;

import dev.thanh.spring_ai.enums.MessageRole;
import dev.thanh.spring_ai.enums.ResponseType;
import dev.thanh.spring_ai.exception.RateLimitException;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@Slf4j(topic = "CHAT-SERVICE")
@RequiredArgsConstructor
public class ChatService {

    private final RedisStreamService redisStreamService;
    private final LlmServicePort llmService;
    private final UuidV7Generator uuidV7Generator;
    private final ChatSessionService chatSessionService;
    private final SessionActivityService sessionActivityService;
    private final Executor virtualThreadExecutor;
    private final RateLimitService rateLimitService;
    private final ChatMetricsService chatMetrics;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String modelName;

    public Flux<ChatResponse> chatStream(ChatMessageRequest request, String userId) {
        // Rate limit check first — throws RateLimitException (→ HTTP 429) trước khi vào pipeline
        rateLimitService.checkTokenBucket(userId);

        // ── Metrics: đếm request + bắt đầu đo stream duration ──
        // Khai báo NGOÀI try để catch block có thể stop đúng instance này
        chatMetrics.incrementTotalRequests();
        chatMetrics.getActiveStreams().incrementAndGet();
        Timer.Sample streamTimer = chatMetrics.startStreamTimer();

        try {

            boolean isNewSession = !StringUtils.hasText(request.sessionId());

            String sessionId = chatSessionService.getOrCreateSession(request.sessionId(), userId);

            // Touch session activity — fire-and-forget async để Redis failure KHÔNG block request path.
            // Nếu Redis lỗi, SessionActivityService đã catch + log WARN → DB sẽ có stale updatedAt (acceptable).
            CompletableFuture.runAsync(
                () -> sessionActivityService.touchSession(userId, sessionId),
                virtualThreadExecutor
            );

            CompletableFuture<List<Message>> historyFuture = loadHistoryAsync(sessionId, isNewSession);

            MessageDTO userMessage = buildUserMessage(sessionId, request.message());
            redisStreamService.pushToStream(userMessage);
            chatMetrics.incrementRedisStreamPushed();

            return Mono.fromFuture(historyFuture)
                    .flatMapMany(history -> {
                        log.info("History loaded. Size: {}", history.size());

                        // ── Pre-flight: CHECK ONLY — không increment ──
                        // Chỉ kiểm tra xem user đã vượt quota chưa, không cộng thêm.
                        // Post-flight: totalTokens thực tế từ Gemini metadata
                        // sẽ được cộng bởi LlmService.doFinally() → RateLimitService.consumeTokens()
                        rateLimitService.checkDailyTokenQuota(userId);

                        return generateStreamResponse(request.message(), history, sessionId, userMessage,
                                isNewSession, userId);
                    })
                    .onErrorResume(RateLimitException.class, e -> Flux.error(e))
                    .onErrorResume(e -> !(e instanceof RateLimitException), e -> {
                        // Phân loại lỗi: client disconnect → WARN, infrastructure error → ERROR
                        // Đảm bảo production vẫn trigger alert cho lỗi thật (LLM timeout, DB failure)
                        // mà không bị noise từ client tự ngắt kết nối
                        chatMetrics.incrementStreamErrors();
                        if (isClientDisconnect(e)) {
                            log.warn("Client disconnected during stream: {}", e.getMessage());
                        } else {
                            log.error("Chat stream pipeline error: {}", e.getMessage());
                        }
                        return Flux.just(ChatResponse.builder()
                                .sessionId(sessionId)
                                .content("Xin lỗi, đã xảy ra lỗi: " + e.getMessage())
                                .role(MessageRole.ASSISTANT)
                                .type(ResponseType.ERROR)
                                .timestamp(ZonedDateTime.now())
                                .build());
                    })
                    // ── Metrics: dừng timer + giảm active streams khi stream kết thúc ──
                    .doFinally(signal -> {
                        chatMetrics.getActiveStreams().decrementAndGet();
                        chatMetrics.stopStreamTimer(streamTimer);
                    });
        } catch (Exception e) {
            log.error("Chat stream initialization error: ", e);
            chatMetrics.incrementStreamErrors();
            chatMetrics.getActiveStreams().decrementAndGet();
            chatMetrics.stopStreamTimer(streamTimer);
            return Flux.just(ChatResponse.builder()
                    .content("Xin lỗi, không thể khởi tạo chat: " + e.getMessage())
                    .role(MessageRole.ASSISTANT)
                    .type(ResponseType.ERROR)
                    .timestamp(ZonedDateTime.now())
                    .build());
        }
    }

    private Flux<ChatResponse> generateStreamResponse(String userMessage, List<Message> history,
            String sessionId, MessageDTO userMessageDTO, boolean isNewSession, String userId) {
        Flux<ChatResponse> contentStream = createContentStream(userMessage, history, sessionId,
                userMessageDTO, userId);

        if (isNewSession) {
            Flux<ChatResponse> titleStream = createTitleStream(userMessage, sessionId);
            return Flux.merge(contentStream, titleStream);
        }

        return contentStream;
    }

    private Flux<ChatResponse> createContentStream(String userMessage, List<Message> history,
            String sessionId, MessageDTO userMessageDTO, String userId) {
        StringBuilder contentCollector = new StringBuilder();
        return llmService.streamResponse(userMessage, history, userId)
                .doOnNext(contentCollector::append) // Collect tokens regardless of downstream state
                .map(token -> buildContentResponse(sessionId, token))
                .doFinally(signal -> handleStreamCompletion(signal, sessionId, contentCollector.toString(),
                        userMessageDTO));
    }

    private Flux<ChatResponse> createTitleStream(String userMessage, String sessionId) {
        return llmService.generateTitle(userMessage)
                .doOnNext(title -> chatSessionService.updateSessionTitle(sessionId, title))
                .map(title -> buildTitleResponse(sessionId, title))
                .onErrorResume(e -> {
                    log.warn("Failed to generate title for session {}", sessionId, e);
                    return Mono.empty();
                })
                .flux();
    }

    private void handleStreamCompletion(SignalType signal, String sessionId, String aiContent, MessageDTO userMessage) {
        if (signal == SignalType.ON_COMPLETE) {
            persistChatMessages(sessionId, aiContent, userMessage);
        } else if (signal == SignalType.CANCEL) {
            log.info("Client disconnected from session {}", sessionId);
        }
    }

    private void persistChatMessages(String sessionId, String aiContent, MessageDTO userMessage) {
        CompletableFuture.runAsync(() -> {
            MessageDTO assistantMessage = buildAssistantMessage(sessionId, aiContent);
            redisStreamService.pushToStream(assistantMessage);
            chatMetrics.incrementRedisStreamPushed();
            redisStreamService.updateHistoryCachePipeline(userMessage, assistantMessage);
        }, virtualThreadExecutor);
    }

    private CompletableFuture<List<Message>> loadHistoryAsync(String sessionId, boolean isNewSession) {
        return CompletableFuture.supplyAsync(
                () -> chatSessionService.prepareHistory(sessionId, isNewSession),
                virtualThreadExecutor).exceptionally(e -> {
                    log.error("Failed to load history for session {}: ", sessionId, e);
                    return Collections.emptyList();
                });
    }

    private MessageDTO buildUserMessage(String sessionId, String content) {
        return MessageDTO.builder()
                .id(uuidV7Generator.generate().toString())
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private MessageDTO buildAssistantMessage(String sessionId, String content) {
        return MessageDTO.builder()
                .id(uuidV7Generator.generate().toString())
                .sessionId(sessionId)
                .role(MessageRole.ASSISTANT)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ChatResponse buildContentResponse(String sessionId, String token) {
        return ChatResponse.builder()
                .sessionId(sessionId)
                .content(token)
                .role(MessageRole.ASSISTANT)
                .type(ResponseType.CONTENT)
                .timestamp(ZonedDateTime.now())
                .build();
    }

    private ChatResponse buildTitleResponse(String sessionId, String title) {
        return ChatResponse.builder()
                .sessionId(sessionId)
                .title(title)
                .type(ResponseType.TITLE)
                .build();
    }

    /**
     * Phân biệt lỗi do client tự ngắt kết nối (Connection reset, Broken pipe)
     * với lỗi infrastructure thực sự (LLM timeout, DB failure).
     * Client disconnect → log.warn (không trigger alert)
     * Infrastructure error → log.error (trigger alert trên Grafana/Alertmanager)
     */
    private boolean isClientDisconnect(Throwable e) {
        String msg = e.getMessage();
        if (msg != null) {
            String lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("connection reset")
                    || lowerMsg.contains("broken pipe")
                    || lowerMsg.contains("an established connection was aborted")
                    || lowerMsg.contains("client disconnected")) {
                return true;
            }
        }
        // IOException hoặc cause là IOException thường là network issue từ phía client
        Throwable cause = e.getCause();
        return (e instanceof java.io.IOException)
                || (cause instanceof java.io.IOException);
    }

}
