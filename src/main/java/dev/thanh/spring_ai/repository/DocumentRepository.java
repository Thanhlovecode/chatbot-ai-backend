package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.Document;
import dev.thanh.spring_ai.enums.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByStatus(DocumentStatus status, Pageable pageable);

    long countByStatus(DocumentStatus status);

    /** Sum of all chunk_count across ACTIVE documents, grouped by topic. */
    @Query("SELECT d.topic, SUM(d.chunkCount) FROM Document d WHERE d.status = 'ACTIVE' GROUP BY d.topic")
    java.util.List<Object[]> sumChunksByTopic();
}
