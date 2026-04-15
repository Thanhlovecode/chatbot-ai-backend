package dev.thanh.spring_ai.test;

import dev.thanh.spring_ai.service.LlmServicePort;
import dev.thanh.spring_ai.service.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;


/**
 * Mock LLM Service — giả lập Gemini API streaming cho load testing.
 * <p>
 * Chỉ active khi {@code llm.mock.enabled=true} (profile test).
 * <p>
 * <b>Full metrics parity với LlmService thật:</b>
 * <ul>
 *   <li>{@code llm.stream.ttfb} — Time To First Byte timer</li>
 *   <li>{@code llm.stream.status} — success/error counter</li>
 *   <li>{@code llm.token.usage} — simulated token usage summary</li>
 *   <li>Post-flight: {@code RateLimitService.consumeTokens()} — quota tracking</li>
 * </ul>
 * <p>
 * Giả lập realistic:
 * <ul>
 *   <li>Initial delay (TTFB) — random giữa min/max delay</li>
 *   <li>Token-by-token emission — interval giống streaming thật</li>
 *   <li>Configurable failure rate — giả lập occasional errors</li>
 * </ul>
 */
@Service
@Slf4j(topic = "MOCK-LLM")
@ConditionalOnProperty(name = "llm.mock.enabled", havingValue = "true")
public class MockLlmService implements LlmServicePort {

    // ── Metric name constants — parity với LlmService thật ──────────────
    private static final class Metrics {
        static final String STREAM_STATUS = "llm.stream.status";
        static final String TOKEN_USAGE = "llm.token.usage";
    }

    /**
     * Simulated input/output token ratio.
     * Gemini thường dùng ~3x prompt tokens cho context + system prompt,
     * nên totalTokens ≈ tokensPerResponse * SIMULATED_TOKEN_MULTIPLIER.
     */
    private static final int SIMULATED_TOKEN_MULTIPLIER = 4;

    private final MockLlmProperties props;
    private final MeterRegistry meterRegistry;
    private final RateLimitService rateLimitService;
    private final Executor virtualThreadExecutor;

    private static final String[] FAKE_TOKENS = {
            "Đây ", "là ", "câu ", "trả ", "lời ", "giả ", "lập ",
            "từ ", "Mock ", "LLM ", "Service. ", "Hệ ", "thống ",
            "đang ", "được ", "test ", "hiệu ", "năng ", "cao. ",
            "Virtual ", "Thread ", "giúp ", "xử ", "lý ", "hàng ",
            "nghìn ", "request ", "đồng ", "thời ", "một ", "cách ",
            "hiệu ", "quả. ", "Redis ", "Stream ", "pipeline ",
            "hoạt ", "động ", "tốt. "
    };

    public MockLlmService(MockLlmProperties props,
                           MeterRegistry meterRegistry,
                           RateLimitService rateLimitService,
                           Executor virtualThreadExecutor) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.rateLimitService = rateLimitService;
        this.virtualThreadExecutor = virtualThreadExecutor;
        log.info("MockLlmService initialized — TTFB: {}–{}ms, tokens: {}, interval: {}ms, failRate: {}%",
                props.getMinDelayMs(), props.getMaxDelayMs(),
                props.getTokensPerResponse(), props.getTokenIntervalMs(),
                props.getFailureRate() * 100);
    }

    @Override
    public Flux<String> streamResponse(String userMsg, List<Message> history, String userId) {
        // Simulated total token count (prompt tokens + output tokens)
        int simulatedTotalTokens = props.getTokensPerResponse() * SIMULATED_TOKEN_MULTIPLIER;

        // Giả lập occasional failure
        if (ThreadLocalRandom.current().nextDouble() < props.getFailureRate()) {
            log.warn("Mock LLM simulating failure for message: '{}'",
                    userMsg.substring(0, Math.min(30, userMsg.length())));
            meterRegistry.counter(Metrics.STREAM_STATUS, "result", "error",
                    "type", "SimulatedFailure").increment();
            return Flux.error(new RuntimeException("Mock LLM simulated failure"));
        }

        // Random Time To First Byte (giả lập delay, KHÔNG đo TTFB vì mock trả rất nhanh)
        int ttfb = ThreadLocalRandom.current().nextInt(props.getMinDelayMs(), props.getMaxDelayMs() + 1);

        log.debug("Mock LLM streaming: ttfb={}ms, tokens={}, interval={}ms, simulatedTotalTokens={}",
                ttfb, props.getTokensPerResponse(), props.getTokenIntervalMs(), simulatedTotalTokens);

        return Flux.interval(Duration.ofMillis(props.getTokenIntervalMs()))
                .take(props.getTokensPerResponse())
                .map(i -> FAKE_TOKENS[(int) (i % FAKE_TOKENS.length)])
                .delaySubscription(Duration.ofMillis(ttfb))
                // Buffer 256 tokens để chịu được downstream SSE client chậm (nhất quán với LlmService thật)
                .onBackpressureBuffer(256)

                .doOnSubscribe(s -> log.debug("Mock LLM stream subscribed"))

                // ── Stream status + post-flight token quota (parity với LlmService.doFinally) ──
                .doOnComplete(() -> meterRegistry.counter(Metrics.STREAM_STATUS, "result", "success",
                        "type", "none").increment())
                .doOnError(e -> meterRegistry.counter(Metrics.STREAM_STATUS, "result", "error",
                        "type", e.getClass().getSimpleName()).increment())
                .doFinally(signal -> {
                    // Post-flight: consume simulated tokens (parity với LlmService)
                    CompletableFuture.runAsync(() -> {
                        rateLimitService.consumeTokens(userId, simulatedTotalTokens);
                        meterRegistry.summary(Metrics.TOKEN_USAGE).record(simulatedTotalTokens);
                        log.debug("Post-flight quota: {} simulated tokens consumed for user={}",
                                simulatedTotalTokens, userId);
                    }, virtualThreadExecutor);
                });
    }

    @Override
    public Mono<String> generateTitle(String userMsg) {
        String truncated = userMsg.substring(0, Math.min(25, userMsg.length()));
        return Mono.just("LoadTest — " + truncated)
                .delayElement(Duration.ofMillis(
                        ThreadLocalRandom.current().nextInt(50, 200)))
                .doOnNext(title -> log.debug("Mock LLM generated title: '{}'", title));
    }
}
