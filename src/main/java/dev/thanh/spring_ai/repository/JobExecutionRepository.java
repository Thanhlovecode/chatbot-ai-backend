package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.JobExecution;
import dev.thanh.spring_ai.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    /** Most recent execution for a given logical job. */
    Optional<JobExecution> findTopByJobIdOrderByStartedAtDesc(String jobId);

    List<JobExecution> findByStatus(JobStatus status);

    long countByStatus(JobStatus status);

    /** Count successful executions since midnight today. */
    @Query("SELECT COUNT(j) FROM JobExecution j WHERE j.status = 'SUCCESS' AND j.startedAt >= :since")
    long countSuccessSince(@Param("since") LocalDateTime since);

    /** Count failed executions since midnight today. */
    @Query("SELECT COUNT(j) FROM JobExecution j WHERE j.status = 'FAILED' AND j.startedAt >= :since")
    long countFailedSince(@Param("since") LocalDateTime since);

    /**
     * Aggregate crawl history per calendar day for the last N days.
     * Returns rows: [date (Date), successCount (Long), failCount (Long)]
     */
    @Query(nativeQuery = true, value = """
            SELECT CAST(started_at AS DATE)                                  AS date,
                   COUNT(CASE WHEN status = 'SUCCESS' THEN 1 END)::int       AS success_count,
                   COUNT(CASE WHEN status = 'FAILED'  THEN 1 END)::int       AS fail_count
              FROM job_executions
             WHERE started_at >= :since
             GROUP BY CAST(started_at AS DATE)
             ORDER BY date ASC
            """)
    List<Object[]> findDailyHistory(@Param("since") LocalDateTime since);
}
