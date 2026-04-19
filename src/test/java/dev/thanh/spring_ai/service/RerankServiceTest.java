package dev.thanh.spring_ai.service;

import com.cohere.api.CohereApiClient;
import com.cohere.api.requests.RerankRequest;
import com.cohere.api.types.RerankResponse;
import com.cohere.api.types.RerankResponseResultsItem;
import dev.thanh.spring_ai.config.HybridRagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RerankService unit tests — focuses on:
 * 1. Cohere API failure → fallback to vector-order top-K (fail-safe)
 * 2. Index mapping correctness (reranked order matches original candidates)
 * 3. Empty input guard
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RerankService — Unit Tests")
class RerankServiceTest {

    @Mock private CohereApiClient cohereClient;
    @Mock private HybridRagProperties ragProps;

    private RerankService service;

    @BeforeEach
    void setUp() {
        service = new RerankService(cohereClient, ragProps);
    }

    private List<Document> buildCandidates(int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(Document.builder()
                    .id("doc-" + i)
                    .text("Content of document " + i)
                    .metadata("source", "file-" + i + ".pdf")
                    .score((double) (count - i) / count)
                    .build());
        }
        return docs;
    }

    @Test
    @DisplayName("empty candidates — should return empty immediately without calling Cohere")
    void emptyCandidates_ShouldReturnEmpty() {
        List<Document> result = service.rerank("query", List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(cohereClient);
    }

    @Test
    @DisplayName("🔴 Cohere API down — should fallback to top-K by vector score order")
    void cohereDown_ShouldFallbackToVectorOrder() {
        List<Document> candidates = buildCandidates(5);
        when(ragProps.getRerankTopK()).thenReturn(3);
        when(ragProps.getRerankModel()).thenReturn("rerank-v3.5");
        when(cohereClient.rerank(any(RerankRequest.class)))
                .thenThrow(new RuntimeException("Cohere API timeout"));

        List<Document> result = service.rerank("query", candidates);

        // Fallback: returns first 3 candidates in original vector order
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getId()).isEqualTo("doc-0");
        assertThat(result.get(1).getId()).isEqualTo("doc-1");
        assertThat(result.get(2).getId()).isEqualTo("doc-2");
    }

    @Test
    @DisplayName("happy path — should return reranked documents with Cohere scores")
    void happyPath_ShouldReturnReranked() {
        List<Document> candidates = buildCandidates(5);
        when(ragProps.getRerankTopK()).thenReturn(2);
        when(ragProps.getRerankModel()).thenReturn("rerank-v3.5");

        // Cohere reranks: doc-3 is most relevant, then doc-1
        RerankResponseResultsItem item1 = RerankResponseResultsItem.builder()
                .index(3).relevanceScore(0.95).build();
        RerankResponseResultsItem item2 = RerankResponseResultsItem.builder()
                .index(1).relevanceScore(0.82).build();
        RerankResponse response = RerankResponse.builder()
                .results(List.of(item1, item2))
                .build();
        when(cohereClient.rerank(any(RerankRequest.class))).thenReturn(response);

        List<Document> result = service.rerank("query", candidates);

        assertThat(result).hasSize(2);
        // First result = doc-3 (reranked to top)
        assertThat(result.get(0).getId()).isEqualTo("doc-3");
        assertThat(result.get(0).getScore()).isEqualTo(0.95);
        // Second result = doc-1
        assertThat(result.get(1).getId()).isEqualTo("doc-1");
        assertThat(result.get(1).getScore()).isEqualTo(0.82);
    }

    @Test
    @DisplayName("topK > candidates.size — should clamp to candidates.size")
    void topKClamped_ShouldNotExceedCandidateSize() {
        List<Document> candidates = buildCandidates(2);
        when(ragProps.getRerankTopK()).thenReturn(10); // larger than candidates
        when(ragProps.getRerankModel()).thenReturn("rerank-v3.5");

        // Verify the request sent to Cohere has topN=2 (clamped)
        RerankResponseResultsItem item = RerankResponseResultsItem.builder()
                .index(0).relevanceScore(0.9).build();
        RerankResponse response = RerankResponse.builder()
                .results(List.of(item))
                .build();
        when(cohereClient.rerank(any(RerankRequest.class))).thenReturn(response);

        service.rerank("query", candidates);

        ArgumentCaptor<RerankRequest> captor = ArgumentCaptor.forClass(RerankRequest.class);
        verify(cohereClient).rerank(captor.capture());
        assertThat(captor.getValue().getTopN().orElse(0)).isEqualTo(2);
    }
}
