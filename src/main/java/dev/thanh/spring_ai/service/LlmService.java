package dev.thanh.spring_ai.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@ConditionalOnProperty(name = "llm.mock.enabled", havingValue = "false", matchIfMissing = true)
public class LlmService implements LlmServicePort {

    private static final String RESILIENCE_NAME = "llm-gemini";

    private final ChatClient chatClient;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final MeterRegistry meterRegistry;

    @Value("classpath:prompts/rag-prompt.st")
    private Resource ragSystemPrompt;

    public LlmService(ChatClient chatClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            BulkheadRegistry bulkheadRegistry,
            MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_NAME);
        this.rateLimiter = rateLimiterRegistry.rateLimiter(RESILIENCE_NAME);
        this.bulkhead = bulkheadRegistry.bulkhead(RESILIENCE_NAME);
        this.meterRegistry = meterRegistry;
        log.info("LlmService initialized — CB: {}, RL: limitForPeriod={}, BH: maxConcurrent={}",
                RESILIENCE_NAME,
                rateLimiter.getRateLimiterConfig().getLimitForPeriod(),
                bulkhead.getBulkheadConfig().getMaxConcurrentCalls());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // streamResponse — Main chat stream với full Resilience4j stack
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public Flux<String> streamResponse(String userMsg, String ragContext, List<Message> history) {
        log.info("Requesting Gemini stream. UserMsg length: {}, RAG Context length: {}",
                userMsg.length(), ragContext.length());

        AtomicBoolean hasEmittedData = new AtomicBoolean(false);
        AtomicBoolean ttfbStopped = new AtomicBoolean(false);

        // Đo TTFB (Time To First Byte) — từ lúc gọi method → token đầu tiên
        // Bao gồm thời gian xếp hàng RL/BH → phản ánh TTFB thực từ góc user
        Timer.Sample ttfbTimer = Timer.start(meterRegistry);

        return chatClient.prompt()
                .system(s -> s.text(ragSystemPrompt).param("information", ragContext))
                .messages(history)
                .user(userMsg)
                .stream()
                .content()
                .doOnSubscribe(s -> log.info("Gemini stream subscribed"))

                // ── Đánh dấu đã emit data + ghi TTFB metric ──
                .doOnNext(token -> {
                    if (hasEmittedData.compareAndSet(false, true)) {
                        if (ttfbStopped.compareAndSet(false, true)) {
                            ttfbTimer.stop(meterRegistry.timer("llm.stream.ttfb"));
                        }
                        log.debug("First token received. Retry disabled from this point.");
                    }
                })

                // Buffer 256 tokens để chịu được downstream SSE client chậm (backpressure)
                .onBackpressureBuffer(256)

                // ── TIMEOUT 2 PHA ──────────────────────────────────────
                // Phase 1: Đợi tối đa 30s cho token đầu tiên (Gemini cold start / thinking)
                // Phase 2: Từ token thứ 2 trở đi, chỉ cho phép idle tối đa 5s giữa các token
                .timeout(Mono.delay(Duration.ofSeconds(30)), v -> Mono.delay(Duration.ofSeconds(5)))

                // ── Resilience4j Stack: bọc từ trong ra ngoài ──────────
                // Thứ tự subscribe (ngoài → trong): RL → BH → CB → stream
                // CB trong cùng → chỉ đếm failure từ Gemini, KHÔNG bị RL/BH rejection nhiễu
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker)) // Trong: failure detection
                .transformDeferred(BulkheadOperator.of(bulkhead)) // Giữa: concurrent limit
                .transformDeferred(RateLimiterOperator.of(rateLimiter)) // Ngoài: RPM throttle

                // ── RETRY: ngoài RL — mỗi attempt đi lại qua RL→BH→CB ──
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                        .jitter(0.5)
                        .filter(error -> isSafeToRetry(error, hasEmittedData.get()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))

                // ── Metrics: phân loại kết quả stream ──
                .doOnComplete(() -> meterRegistry.counter("llm.stream.status", "result", "success").increment())
                .doOnError(e -> meterRegistry.counter("llm.stream.status", "result", "error",
                        "type", e.getClass().getSimpleName()).increment())

                // ── Fallback: stop TTFB timer nếu stream kết thúc mà chưa có token nào ──
                .doFinally(signal -> {
                    if (ttfbStopped.compareAndSet(false, true)) {
                        ttfbTimer.stop(meterRegistry.timer("llm.stream.ttfb",
                                "status", "no_token"));
                    }
                })

                // ── Terminal error handler: normalize lỗi cuối cùng ──
                .onErrorResume(e -> handleFinalTerminalError(e, hasEmittedData.get()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // generateTitle — Chỉ CircuitBreaker, KHÔNG cần RateLimiter/Bulkhead
    // Model gemini-2.0-flash-lite có RPM quota riêng.
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public Mono<String> generateTitle(String userMsg) {
        String titlePrompt = """
                Summarize the following user message into a short title (5-10 words).
                Do not use quotes.
                Language must match the user message.
                Message: %s
                """.formatted(userMsg);
        return Mono.fromCallable(() -> chatClient.prompt()
                .options(GoogleGenAiChatOptions.builder()
                        .model("gemini-2.0-flash-lite")
                        .build())
                .user(titlePrompt)
                .call()
                .content())
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(10))
                .map(String::trim)
                // ── Circuit Breaker: detect slow blocking call timeout ──
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, e -> {
                    log.warn("Circuit breaker '{}' is OPEN — generateTitle rejected. Returning empty.",
                            RESILIENCE_NAME);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to generate title", e);
                    return Mono.empty();
                });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isSafeToRetry — Whitelist nghiêm ngặt
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Kiểm soát retry cực kỳ khắt khe.
     * <p>
     * KHÔNG BAO GIỜ retry nếu:
     * <ul>
     * <li>Đã emit data lên client → retry sẽ tạo text bị lặp trên UI</li>
     * <li>Lỗi từ hàng rào bảo vệ nội bộ (BH/RL/CB) → retry vô nghĩa</li>
     * </ul>
     * CHỈ retry nếu:
     * <ul>
     * <li>{@link TimeoutException} — Gemini idle quá 10s</li>
     * <li>{@link IOException} — network failure</li>
     * </ul>
     */
    private boolean isSafeToRetry(Throwable e, boolean hasEmitted) {
        if (hasEmitted) {
            log.warn("Stream đứt giữa chừng sau khi đã emit data. Hủy retry để không lặp text trên UI.");
            return false;
        }

        if (e instanceof BulkheadFullException
                || e instanceof RequestNotPermitted
                || e instanceof CallNotPermittedException) {
            return false;
        }

        // 🔍 BÓC LỚP VỎ ĐỂ LẤY EXCEPTION GỐC (ROOT CAUSE) TỪ SPRING
        Throwable rootCause = NestedExceptionUtils.getRootCause(e);
        Throwable actualError = (rootCause != null) ? rootCause : e;

        // Bắt lỗi TCP Timeout hoặc mất kết nối mạng thật sự
        return actualError instanceof TimeoutException
                || actualError instanceof IOException
                || actualError instanceof java.net.ConnectException;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // handleFinalTerminalError — Normalize lỗi cuối cùng cho client
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Xử lý lỗi cuối cùng sau khi retry exhausted.
     * Phân loại exception để trả message phù hợp cho client.
     *
     * @param e          exception cuối cùng
     * @param hasEmitted true nếu đã gửi ít nhất 1 token lên client
     */
    private Flux<String> handleFinalTerminalError(Throwable e, boolean hasEmitted) {
        // Đã emit data → stream bị cắt giữa chừng
        if (hasEmitted) {
            log.error("Stream đứt giữa chừng: {}", e.getMessage());
            return Flux.just("\n\n[Lỗi: Kết nối bị gián đoạn. Vui lòng thử lại.]");
        }

        if (e instanceof CallNotPermittedException) {
            log.warn("CB '{}' is OPEN — rejected. State: {}",
                    RESILIENCE_NAME, circuitBreaker.getState());
            return Flux.just("Hệ thống AI tạm thời không khả dụng, vui lòng thử lại sau ít phút.");
        }

        if (e instanceof RequestNotPermitted) {
            log.warn("RateLimiter '{}' rejected — API quota exhausted", RESILIENCE_NAME);
            return Flux.just("Hệ thống đang quá tải, vui lòng thử lại sau vài giây.");
        }

        if (e instanceof BulkheadFullException) {
            log.warn("Bulkhead '{}' full — max concurrent calls reached", RESILIENCE_NAME);
            return Flux.just("Hệ thống đang bận xử lý nhiều yêu cầu, vui lòng thử lại sau.");
        }

        log.error("LlmService terminal error: ", e);
        return Flux.just("Xin lỗi, hệ thống AI gặp lỗi kết nối: " + e.getMessage());
    }
}
