package dev.thanh.spring_ai.service;

/**
 * Contract cho RAG search — cho phép swap giữa Real (Qdrant + Embedding + Rerank) và Mock khi load test.
 * <p>
 * - Production: {@link RagService} gọi Gemini Embedding + Qdrant + Cohere Rerank thật
 * - Load test:  {@link dev.thanh.spring_ai.service.mock.MockRagService} giả lập delay + trả fake context
 * <p>
 * Chỉ extract {@code searchSimilarity} — method duy nhất mà {@link ChatService} cần.
 * {@code storeDataFile()} nằm riêng trên {@link RagService} vì load test không upload file.
 */
public interface RagServicePort {

    /**
     * Tìm kiếm context liên quan từ knowledge base.
     *
     * @param query câu hỏi của user
     * @return context string để gửi cho LLM, hoặc fallback message nếu không tìm thấy
     */
    String searchSimilarity(String query);
}
