package dev.thanh.spring_ai.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.thanh.spring_ai.enums.DocumentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks metadata for every file uploaded to the RAG knowledge base.
 * The actual vectors are stored in Qdrant, keyed by {@code fileId}.
 */
@Entity
@Table(name = "documents")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Document extends BaseEntity<UUID> {

    @Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "chunk_count", nullable = false)
    @Builder.Default
    private Integer chunkCount = 0;

    @Column(length = 100)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DocumentStatus status = DocumentStatus.PROCESSING;

    /**
     * Matches {@code metadata.file_id} stored in Qdrant vector payloads.
     * Used to delete vectors when this document is deleted.
     */
    @Column(name = "file_id", length = 100)
    private String fileId;

    /**
     * UUID of the user who uploaded this file (nullable — user may have been
     * deleted).
     */
    @Column(name = "uploaded_by", columnDefinition = "UUID")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidCreator.getTimeOrderedEpoch();
        }
        if (this.uploadedAt == null) {
            this.uploadedAt = LocalDateTime.now();
        }
    }
}
