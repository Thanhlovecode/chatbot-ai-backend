package dev.thanh.spring_ai.test;

import dev.thanh.spring_ai.service.RagServicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock RAG Service — giả lập Embedding + Qdrant search + Cohere Rerank cho load testing.
 * <p>
 * Chỉ active khi {@code rag.mock.enabled=true} (profile test).
 * Giả lập realistic:
 * <ul>
 *   <li>Latency delay — random giữa min/max delay (100-500ms)</li>
 *   <li>Trả về fake context string giống structure thật</li>
 *   <li>Configurable failure rate — giả lập occasional RAG failures</li>
 * </ul>
 */
@Service
@Slf4j(topic = "MOCK-RAG")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "true")
public class MockRagService implements RagServicePort {

    private final MockRagProperties props;

    private static final String FAKE_CONTEXT = """
            ## Virtual Threads trong Java 21
            Virtual Threads (Project Loom) là lightweight threads do JVM quản lý, \
            cho phép tạo hàng triệu thread mà không tốn tài nguyên OS. \
            Ưu điểm chính: throughput cao cho I/O-bound tasks, code đồng bộ dễ đọc, \
            tương thích ngược với existing APIs.
            
            ## Spring Boot 3.x Integration
            Spring Boot 3.2+ hỗ trợ Virtual Threads qua cấu hình: \
            spring.threads.virtual.enabled=true. Tomcat, Jetty, và task executors \
            tự động chuyển sang Virtual Threads. HikariCP 5.1.0+ đã fix pinning issue \
            với synchronized blocks.
            
            ## Redis Stream Pipeline
            Redis Stream là append-only log structure hỗ trợ consumer groups. \
            XADD thêm message, XREADGROUP để consume. Pending Entries List (PEL) \
            đảm bảo at-least-once delivery. XACK acknowledge sau khi xử lý xong.""";

    @Override
    public String searchSimilarity(String query) {
        // Giả lập occasional failure
        if (ThreadLocalRandom.current().nextDouble() < props.getFailureRate()) {
            log.warn("Mock RAG simulating failure for query: '{}'",
                    query.substring(0, Math.min(30, query.length())));
            return "No specific context available from the internal knowledge base.";
        }

        // Giả lập latency: Embedding + Qdrant + Rerank
        int delay = ThreadLocalRandom.current().nextInt(props.getMinDelayMs(), props.getMaxDelayMs() + 1);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Mock RAG sleep interrupted");
            return "No specific context available from the internal knowledge base.";
        }

        log.debug("Mock RAG search completed: delay={}ms, query='{}'",
                delay, query.substring(0, Math.min(30, query.length())));

        return FAKE_CONTEXT;
    }
}
