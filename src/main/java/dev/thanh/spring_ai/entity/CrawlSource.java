package dev.thanh.spring_ai.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A URL source that the crawler uses to discover and fetch pages.
 */
@Entity
@Table(name = "crawl_sources")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CrawlSource extends BaseEntity<UUID> {

    @Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "base_url", nullable = false, length = 512, unique = true)
    private String baseUrl;

    /** Spring-compatible cron expression. Default: 6 AM daily. */
    @Column(name = "cron_schedule", length = 100)
    @Builder.Default
    private String cronSchedule = "0 0 6 * * *";

    @Column(name = "max_depth", nullable = false)
    @Builder.Default
    private Integer maxDepth = 3;

    /** ACTIVE = enabled for scheduled crawl; INACTIVE = paused. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private CrawlSourceStatus status = CrawlSourceStatus.ACTIVE;

    @Column(name = "last_crawl_at")
    private LocalDateTime lastCrawlAt;

    @Column(name = "pages_crawled", nullable = false)
    @Builder.Default
    private Integer pagesCrawled = 0;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidCreator.getTimeOrderedEpoch();
        }
    }
}
