package dev.thanh.spring_ai.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class LlmService {

    private static final String CB_NAME = "llm-gemini";

    private final ChatClient chatClient;
    private final CircuitBreaker circuitBreaker;

    @Value("classpath:prompts/rag-prompt.st")
    private Resource ragSystemPrompt;

    public LlmService(ChatClient chatClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.chatClient = chatClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME);
        log.info("LlmService initialized with CircuitBreaker '{}'", CB_NAME);
    }

    public Flux<String> streamResponse(String userMsg, String ragContext, List<Message> history) {
        log.info("Requesting Gemini stream. UserMsg length: {}, RAG Context length: {}", userMsg.length(),
                ragContext.length());
        return chatClient.prompt()
                .system(s -> s.text(ragSystemPrompt).param("information", ragContext))
                .messages(history) // Lịch sử chat
                .user(userMsg) // Câu hỏi mới
                .stream()
                .content()
                .doOnSubscribe(s -> log.info("Gemini stream subscribed"))
                .doOnNext(token -> log.debug("Gemini received token: '{}' ", token.replace("\n", "\\n")))
                .doOnComplete(() -> log.info("Gemini stream completed"))
                .doOnError(e -> log.error("Gemini stream error signal: ", e))
                .timeout(Duration.ofSeconds(25)) // 1. TRONG CB — timeout được đếm là failure
                .retryWhen(Retry.backoff(1, Duration.ofSeconds(2)) // 2. TRONG CB — retry không chạy khi CB OPEN
                        .filter(this::isRetryableError)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                // ── CB bọc ngoài cùng: timeout + retry đều nằm trong CB scope ──
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, e -> {
                    // CB đang OPEN: fail-fast, không chờ timeout
                    log.warn("Circuit breaker '{}' is OPEN — Gemini request rejected immediately. State: {}",
                            CB_NAME, circuitBreaker.getState());
                    return Flux.just("Hệ thống AI tạm thời không khả dụng, vui lòng thử lại sau ít phút.");
                })
                .onErrorResume(e -> {
                    log.error("LlmService final stream error handling: ", e);
                    return Flux.just("Xin lỗi, hệ thống AI đang bận hoặc gặp lỗi kết nối: " + e.getMessage());
                });
    }

    public Mono<String> generateTitle(String userMsg) {
        String titlePrompt = """
                Summarize the following user message into a short title (5-10 words).
                Do not use quotes.
                Language must match the user message.
                Message: %s
                """.formatted(userMsg);
        return Mono.fromCallable(() -> chatClient.prompt()
                .user(titlePrompt)
                .call()
                .content())
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(10))
                .map(String::trim)
                // ── Circuit Breaker: detect slow blocking call timeout ──
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, e -> {
                    log.warn("Circuit breaker '{}' is OPEN — generateTitle rejected. Returning empty.", CB_NAME);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to generate title", e);
                    return Mono.empty();
                });
    }

    private boolean isRetryableError(Throwable ex) {
        return ex instanceof TimeoutException
                || ex instanceof IOException;
    }
}
