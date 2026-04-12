package dev.thanh.spring_ai.test;

import dev.thanh.spring_ai.service.LlmServicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock LLM Service — giả lập Gemini API streaming cho load testing.
 * <p>
 * Chỉ active khi {@code llm.mock.enabled=true} (profile test).
 * Giả lập realistic:
 * <ul>
 *   <li>Initial delay (TTFB) — random giữa min/max delay</li>
 *   <li>Token-by-token emission — interval giống streaming thật</li>
 *   <li>Configurable failure rate — giả lập occasional errors</li>
 * </ul>
 */
@Service
@Slf4j(topic = "MOCK-LLM")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.mock.enabled", havingValue = "true")
public class MockLlmService implements LlmServicePort {

    private final MockLlmProperties props;

    private static final String[] FAKE_TOKENS = {
            "Đây ", "là ", "câu ", "trả ", "lời ", "giả ", "lập ",
            "từ ", "Mock ", "LLM ", "Service. ", "Hệ ", "thống ",
            "đang ", "được ", "test ", "hiệu ", "năng ", "cao. ",
            "Virtual ", "Thread ", "giúp ", "xử ", "lý ", "hàng ",
            "nghìn ", "request ", "đồng ", "thời ", "một ", "cách ",
            "hiệu ", "quả. ", "Redis ", "Stream ", "pipeline ",
            "hoạt ", "động ", "tốt. "
    };

    @Override
    public Flux<String> streamResponse(String userMsg, List<Message> history, String userId) {
        // Giả lập occasional failure
        if (ThreadLocalRandom.current().nextDouble() < props.getFailureRate()) {
            log.warn("Mock LLM simulating failure for message: '{}'",
                    userMsg.substring(0, Math.min(30, userMsg.length())));
            return Flux.error(new RuntimeException("Mock LLM simulated failure"));
        }

        // Random Time To First Byte
        int ttfb = ThreadLocalRandom.current().nextInt(props.getMinDelayMs(), props.getMaxDelayMs() + 1);

        log.debug("Mock LLM streaming: ttfb={}ms, tokens={}, interval={}ms",
                ttfb, props.getTokensPerResponse(), props.getTokenIntervalMs());

        return Flux.interval(Duration.ofMillis(props.getTokenIntervalMs()))
                .take(props.getTokensPerResponse())
                .map(i -> FAKE_TOKENS[(int) (i % FAKE_TOKENS.length)])
                .delaySubscription(Duration.ofMillis(ttfb))
                // Buffer 256 tokens để chịu được downstream SSE client chậm (nhất quán với LlmService thật)
                .onBackpressureBuffer(256)
                .doOnSubscribe(s -> log.debug("Mock LLM stream subscribed"))
                .doOnComplete(() -> log.debug("Mock LLM stream completed"));
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
