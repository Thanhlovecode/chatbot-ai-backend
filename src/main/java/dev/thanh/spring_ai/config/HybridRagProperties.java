package dev.thanh.spring_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties cho Indexing Pipeline.
 *
 * <p>
 * DocumentSplitters.recursive(chunkSize, chunkOverlap) đo bằng CHARACTERS,
 * không phải tokens.
 * 2048 chars ≈ 512 tokens (4 chars/token trung bình).
 *
 * <p>
 * embeddingDimension = 768: dùng Matryoshka output của gemini-embedding-001.
 * Tiết kiệm 4x RAM Qdrant so với 3072, giữ ~95% chất lượng retrieval.
 * Thay đổi giá trị → phải xóa Qdrant collection cũ và re-index toàn bộ.
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class HybridRagProperties {

    // ─── Indexing ─────────────────────────────────────────────────────────────

    /**
     * Max segment size — đơn vị là CHARS (không phải tokens). ~512 tokens × 4
     * chars/token.
     */
    private int chunkSize = 2048;

    /** Max overlap size — đơn vị là CHARS. ~12.5% của chunkSize. */
    private int chunkOverlap = 256;

    /** Tên Qdrant collection. */
    private String collectionName = "spring";

    /**
     * Embedding dimension — phải khớp với Qdrant collection config.
     * Dùng Matryoshka: gemini-embedding-001 giảm xuống 768 (từ default 3072).
     */
    private int embeddingDimension = 768;

    /**
     * Số chunks mỗi batch khi upsert vào VectorStore. Tránh payload quá lớn / rate
     * limit.
     */
    private int upsertBatchSize = 100;

    /** Số pages gộp thành 1 window khi split. Giới hạn peak memory cho PDF lớn. */
    private int pageWindow = 100;

    // ─── Retrieval ────────────────────────────────────────────────────────────

    /**
     * Số candidates lấy từ Qdrant ở bước 1 (vector search).
     * Con số lớn để đảm bảo cross-encoder có đủ ngữ liệu để rerank.
     */
    private int candidateTopK = 10;

    /**
     * Similarity threshold cho bước candidate fetch.
     * Thấp hơn {@code rerankSimilarityThreshold} để không bỏ sót kết quả liên quan
     * xa.
     */
    private double candidateSimilarityThreshold = 0.6;

    /** Số kết quả trả về sau khi cross-encoder rerank — context gửi tới LLM. */
    private int rerankTopK = 3;

    /** Cohere rerank model ID. */
    private String rerankModel = "rerank-v3.5";
}
