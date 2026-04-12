package dev.thanh.spring_ai.service;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.config.HybridRagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagService — Unit Tests")
class RagServiceTest {

    /** documentVectorStore — @Primary, inject cho storeDataFile */
    @Mock
    private VectorStore documentVectorStore;

    /** queryVectorStore — @Qualifier("queryVectorStore"), inject cho searchSimilarity */
    @Mock
    private VectorStore queryVectorStore;

    @Mock
    private RerankService rerankService;

    @Mock
    private DocumentSplitter documentSplitter;

    @Mock
    private UuidV7Generator uuidGenerator;

    @Mock
    private DocumentParserService documentParserService;

    /**
     * RagService tạo thủ công để tránh phụ thuộc Spring context & inject đúng queryVectorStore.
     * Dùng HybridRagProperties thật với default values.
     */
    private RagService ragService;

    private static final String NO_CONTEXT_MSG = "No specific context available from the internal knowledge base.";

    @BeforeEach
    void setUp() {
        HybridRagProperties props = new HybridRagProperties();
        // default: candidateTopK=20, candidateSimilarityThreshold=0.3, rerankTopK=5
        // semanticCacheService = Optional.empty() → cache disabled trong test
        // virtualThreadExecutor = Runnable::run → synchronous execution trong test
        ragService = new RagService(
                documentVectorStore, documentSplitter, props, uuidGenerator,
                documentParserService, java.util.Optional.empty(), (Runnable::run),
                queryVectorStore, rerankService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // searchSimilarity — 3-stage pipeline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline đầy đủ: top-20 → rerank → context được build đúng")
    void searchSimilarity_FullPipeline_ShouldReturnRerankContext() {
        // Given
        Document doc1 = Document.builder().text("Spring Boot simplifies configuration.").metadata(Map.of()).build();
        Document doc2 = Document.builder().text("Dependency injection is a core concept.").metadata(Map.of()).build();
        Document doc3 = Document.builder().text("Spring AI integrates LLMs into Spring.").metadata(Map.of()).build();

        List<Document> candidates = List.of(doc1, doc2, doc3);
        List<Document> rerankedTop2 = List.of(doc3, doc1); // rerank loại doc2

        when(queryVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
        when(rerankService.rerank(anyString(), anyList())).thenReturn(rerankedTop2);

        // When
        String result = ragService.searchSimilarity("What is Spring AI?");

        // Then
        assertThat(result)
                .contains("Spring AI integrates LLMs into Spring.")
                .contains("Spring Boot simplifies configuration.")
                .doesNotContain("Dependency injection");

        verify(queryVectorStore).similaritySearch(any(SearchRequest.class));
        verify(rerankService).rerank(anyString(), anyList());
    }

    @Test
    @DisplayName("khi không có candidates — trả no-context message")
    void searchSimilarity_WhenCandidatesEmpty_ShouldReturnNoContextMessage() {
        when(queryVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(Collections.emptyList());

        String result = ragService.searchSimilarity("Random query");

        assertThat(result).isEqualTo(NO_CONTEXT_MSG);
    }

    @Test
    @DisplayName("khi Qdrant ngoại tuyến — fail-safe trả no-context")
    void searchSimilarity_WhenVectorStoreThrows_ShouldReturnNoContextMessage() {
        when(queryVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("Qdrant connection refused"));

        String result = ragService.searchSimilarity("What is RAG?");

        assertThat(result).isEqualTo(NO_CONTEXT_MSG);
    }


    @Test
    @DisplayName("khi Cohere rerank trả fallback vector results — context vẫn được trả về")
    void searchSimilarity_WhenRerankFallbacksToVectorResults_ShouldReturnContext() {
        Document doc1 = Document.builder().text("Vector fallback content.").metadata(Map.of()).build();
        List<Document> candidates = List.of(doc1);
        List<Document> fallbackResult = List.of(doc1);

        when(queryVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
        when(rerankService.rerank(anyString(), anyList())).thenReturn(fallbackResult);

        String result = ragService.searchSimilarity("Some query");

        assertThat(result).contains("Vector fallback content.");
    }
}
