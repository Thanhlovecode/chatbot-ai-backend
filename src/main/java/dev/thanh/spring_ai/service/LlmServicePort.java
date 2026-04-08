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
     *
     * @param userMsg    câu hỏi của user
     * @param ragContext context từ RAG pipeline
     * @param history    lịch sử chat
     * @return Flux<String> — stream các token
     */
    Flux<String> streamResponse(String userMsg, String ragContext, List<Message> history);

    /**
     * Tạo tiêu đề cho session từ câu hỏi đầu tiên.
     *
     * @param userMsg câu hỏi đầu
     * @return Mono<String> — tiêu đề ngắn gọn
     */
    Mono<String> generateTitle(String userMsg);
}
