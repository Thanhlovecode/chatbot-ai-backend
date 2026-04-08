package dev.thanh.spring_ai.service;

import com.cohere.api.CohereApiClient;
import com.cohere.api.requests.RerankRequest;
import com.cohere.api.types.RerankRequestDocumentsItem;
import com.cohere.api.types.RerankResponse;
import com.cohere.api.types.RerankResponseResultsItem;
import dev.thanh.spring_ai.config.HybridRagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Dịch vụ rerank cross-encoder sử dụng Cohere Rerank API.
 *
 * <p>
 * Nhận danh sách candidates từ vector search (top-20), gửi lên Cohere
 * cross-encoder để tính relevance score chính xác hơn, trả về top-K đã sắp xếp.
 *
 * <p>
 * <b>Fail-safe:</b> Nếu Cohere không khả dụng, fallback về top-K đầu tiên
 * theo vector similarity score (không gây crash pipeline).
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "RERANK-SERVICE")
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
public class RerankService {

    private final CohereApiClient cohereClient;
    private final HybridRagProperties ragProps;

    /**
     * Rerank danh sách candidates theo relevance với query.
     *
     * @param query      câu query gốc của người dùng
     * @param candidates danh sách Document đã lấy từ vector store (top-20)
     * @return danh sách Document đã rerank, giới hạn {@code rerankTopK}
     */
    public List<Document> rerank(String query, List<Document> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        int topK = Math.min(ragProps.getRerankTopK(), candidates.size());
        long startMs = System.currentTimeMillis();

        try {
            List<RerankRequestDocumentsItem> docs = candidates.stream()
                    .map(doc -> RerankRequestDocumentsItem.of(doc.getText()))
                    .toList();

            RerankRequest request = RerankRequest.builder()
                    .query(query)
                    .documents(docs)
                    .model(ragProps.getRerankModel())
                    .topN(topK)
                    .build();

            RerankResponse response = cohereClient.rerank(request);

            List<Document> reranked = new ArrayList<>(topK);
            int rank = 0;
            for (RerankResponseResultsItem item : response.getResults()) {
                int index = item.getIndex();
                double score = item.getRelevanceScore();
                Document original = candidates.get(index);

                reranked.add(Document.builder()
                        .id(original.getId())
                        .text(original.getText())
                        .metadata(original.getMetadata())
                        .score(score)
                        .build());

                log.debug("[RERANK] rank={} score={} source={}",
                        ++rank, String.format("%.4f", score),
                        original.getMetadata().get("source"));
            }

            long latency = System.currentTimeMillis() - startMs;
            log.info("[RERANK] {} → {} docs | model={} | {}ms",
                    candidates.size(), reranked.size(), ragProps.getRerankModel(), latency);

            return reranked;

        } catch (Exception e) {
            log.warn("[RERANK] Cohere unavailable — fallback to top-{} by vector score: {}",
                    topK, e.getMessage());
            return new ArrayList<>(candidates.subList(0, topK)); // hoặc throw tùy SLA
        }
    }
}
