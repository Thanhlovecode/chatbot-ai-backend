package dev.thanh.spring_ai.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.thanh.spring_ai.enums.JobStatus;
import dev.thanh.spring_ai.enums.JobType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records every execution of a background job (scheduled or manually
 * triggered).
 * {@link JobStatus#IDLE} is a computed state — it is never persisted.
 */
@Entity
@Table(name = "job_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class JobExecution extends BaseEntity<UUID> {

    @Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    /** Logical identifier for the job (e.g. "crawl-all"). */
    @Column(name = "job_id", nullable = false, length = 100)
    private String jobId;

    @Column(name = "job_name", nullable = false, length = 255)
    private String jobName;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    @Builder.Default
    private JobType jobType = JobType.SCHEDULED;

    /** RUNNING or SUCCESS or FAILED. IDLE is never stored here. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status;

    /** Elapsed time in milliseconds. Null while RUNNING. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Number of pages processed. Null for non-crawler jobs. */
    @Column(name = "pages_count")
    private Integer pagesCount;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidCreator.getTimeOrderedEpoch();
        }
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }
}
