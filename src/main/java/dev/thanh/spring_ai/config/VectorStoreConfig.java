package dev.thanh.spring_ai.config;

import io.qdrant.client.QdrantClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Cấu hình 2 bean {@link VectorStore} riêng biệt cho 2 mục đích khác nhau:
 *
 * <ul>
 *   <li>{@code documentVectorStore} (@Primary): dùng embedding RETRIEVAL_DOCUMENT
 *       để <b>index</b> (upsert) tài liệu vào Qdrant.</li>
 *   <li>{@code queryVectorStore}: dùng embedding RETRIEVAL_QUERY để <b>search</b>
 *       trong pipeline RAG — đúng task type, tối ưu semantic matching.</li>
 * </ul>
 *
 * <p>Cả 2 VectorStore trỏ vào <b>cùng 1 Qdrant collection</b>.
 * Bộ embedding model khác nhau đảm bảo đúng task type theo khuyến nghị của Gemini.
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.qdrant.collection-name:spring}")
    private String collectionName;

    // ─── Document VectorStore (Indexing) ──────────────────────────────────────

    /**
     * Bean PRIMARY — dùng cho {@link dev.thanh.spring_ai.service.RagService#storeDataFile}.
     * Embed với RETRIEVAL_DOCUMENT task type.
     */
    @Bean
    @Primary
    public QdrantVectorStore documentVectorStore(
            QdrantClient qdrantClient,
            EmbeddingModel documentEmbeddingModel) {

        return QdrantVectorStore.builder(qdrantClient, documentEmbeddingModel)
                .collectionName(collectionName)
                .initializeSchema(true)
                .build();
    }

    // ─── Query VectorStore (Retrieval) ────────────────────────────────────────

    /**
     * Bean QUERY — dùng chỉ cho search trong RAG pipeline.
     * Embed query với RETRIEVAL_QUERY task type → cosine similarity chính xác hơn.
     */
    @Bean
    @Qualifier("queryVectorStore")
    public QdrantVectorStore queryVectorStore(
            QdrantClient qdrantClient,
            @Qualifier("queryEmbedding") EmbeddingModel queryEmbeddingModel) {

        return QdrantVectorStore.builder(qdrantClient, queryEmbeddingModel)
                .collectionName(collectionName)
                .initializeSchema(false)   // collection đã init bởi documentVectorStore
                .build();
    }
}
