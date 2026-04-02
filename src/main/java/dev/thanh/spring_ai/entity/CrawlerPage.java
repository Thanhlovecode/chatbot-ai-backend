package dev.thanh.spring_ai.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.thanh.spring_ai.enums.CrawlerPageStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A page discovered and fetched by the crawler, awaiting admin review.
 * After approval, the page content is ingested into Qdrant asynchronously.
 */
@Entity
@Table(name = "crawler_pages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CrawlerPage extends BaseEntity<UUID> {

    @Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    /** FK to the source that produced this page. */
    @Column(name = "source_id", columnDefinition = "UUID")
    private UUID sourceId;

    @Column(length = 500)
    private String title;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(name = "topic_tag", length = 100)
    private String topicTag;

    @Column(name = "word_count", nullable = false)
    @Builder.Default
    private Integer wordCount = 0;

    @Column(name = "chunk_count", nullable = false)
    @Builder.Default
    private Integer chunkCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private CrawlerPageStatus status = CrawlerPageStatus.PENDING;

    @Column(name = "crawled_at", nullable = false)
    private LocalDateTime crawledAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** UUID of the admin who reviewed this page. */
    @Column(name = "reviewed_by", columnDefinition = "UUID")
    private UUID reviewedBy;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidCreator.getTimeOrderedEpoch();
        }
        if (this.crawledAt == null) {
            this.crawledAt = LocalDateTime.now();
        }
    }
}
