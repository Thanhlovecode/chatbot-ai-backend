package dev.thanh.spring_ai.config;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document processing configuration for the RAG pipeline.
 *
 * <p>Recursive document splitter từ LangChain4j.
 * Dùng overload 2-param: char-based (không cần TokenCountEstimator).
 * Thứ tự split đệ quy: paragraph → line → sentence → word.
 * Overlap chỉ lấy full sentence — không cắt giữa câu.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class DocumentSplitterConfig {

    private final HybridRagProperties ragProperties;

    @Bean
    public DocumentSplitter documentSplitter() {
        return DocumentSplitters.recursive(
                ragProperties.getChunkSize(),    // maxSegmentSizeInChars = 2048
                ragProperties.getChunkOverlap()  // maxOverlapSizeInChars = 256
        );
    }
}
