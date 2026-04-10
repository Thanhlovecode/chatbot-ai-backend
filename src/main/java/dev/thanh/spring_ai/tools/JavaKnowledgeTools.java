package dev.thanh.spring_ai.tools;

import dev.thanh.spring_ai.service.RagServicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agentic RAG Tool — LLM tự quyết định khi nào cần tra cứu knowledge base.
 * <p>
 * Thay vì mọi câu hỏi đều đi qua Qdrant + Reranking (naive RAG),
 * tool này được đăng ký vào ChatClient để Gemini chỉ gọi khi câu hỏi
 * liên quan đến Java / Spring Boot.
 * <p>
 * Delegate 100% logic search cho {@link RagServicePort} (Qdrant + Cohere
 * Rerank).
 * <p>
 * <b>Resilience:</b> Timeout 10s + graceful fallback nếu Qdrant down/chậm
 * → LLM vẫn trả lời bằng general knowledge, stream không bị treo.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "RAG-TOOL")
public class JavaKnowledgeTools {

    private static final int TOOL_TIMEOUT_SECONDS = 10;
    private static final String FALLBACK_MSG = "No internal knowledge retrieved. Answer using general knowledge.";

    private final RagServicePort ragService;
    private final Executor virtualThreadExecutor;

    @Tool(description = """
            Search internal knowledge base for Java and Spring-related technical information.

            Use this tool when the question is about Java, Spring Boot, or backend systems built with them.

            Do NOT use for general knowledge, non-Java languages, or casual conversation.
            """)
    public String searchJavaSpringBootDocs(String query) {
        log.info("🤖 [TOOL CALLED] AI Agent nhận diện chủ đề Java. Đang quét Qdrant với từ khóa: [{}]", query);

        try {
            // Timeout 10s — tránh Qdrant chậm/treo làm block toàn bộ stream
            return CompletableFuture
                    .supplyAsync(() -> ragService.searchSimilarity(query), virtualThreadExecutor)
                    .get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.warn("⏰ [TOOL TIMEOUT] Qdrant không phản hồi trong {}s. Fallback sang general knowledge.",
                    TOOL_TIMEOUT_SECONDS);
            return FALLBACK_MSG;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("🔴 [TOOL INTERRUPTED] RAG search bị interrupt");
            return FALLBACK_MSG;

        } catch (Exception e) {
            log.warn("🔴 [TOOL ERROR] RAG search thất bại: {}", e.getMessage());
            return FALLBACK_MSG;
        }
    }
}
