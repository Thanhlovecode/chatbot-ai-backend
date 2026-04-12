package dev.thanh.spring_ai.service;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Contract cho LLM service — cho phép swap giữa Real Gemini và Mock khi load test.
 * <p>
 * - Production: {@link LlmService} gọi Gemini API thật qua ChatClient + CircuitBreaker
 * - Load test:  {@link dev.thanh.spring_ai.service.mock.MockLlmService} giả lập streaming response
 */
public interface LlmServicePort {

    /**
     * Stream response từ LLM.
     * <p>
     * Nội bộ sử dụng {@code .chatResponse()} để capture token usage metadata từ Gemini,
     * sau đó map về {@code Flux<String>} giữ nguyên contract cũ.
     * <p>
     * Post-flight: sau khi stream hoàn thành, totalTokens từ Gemini metadata
     * được cộng vào daily quota qua {@code RateLimitService.consumeTokens()}.
     *
     * @param userMsg câu hỏi của user
     * @param history lịch sử chat
     * @param userId  user identifier — dùng cho post-flight quota consumption
     * @return Flux<String> — stream các token
     */
    Flux<String> streamResponse(String userMsg, List<Message> history, String userId);

    /**
     * Tạo tiêu đề cho session từ câu hỏi đầu tiên.
     *
     * @param userMsg câu hỏi đầu
     * @return Mono<String> — tiêu đề ngắn gọn
     */
    Mono<String> generateTitle(String userMsg);
}
