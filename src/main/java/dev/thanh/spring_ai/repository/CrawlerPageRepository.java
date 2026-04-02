package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.CrawlerPage;
import dev.thanh.spring_ai.enums.CrawlerPageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface CrawlerPageRepository extends JpaRepository<CrawlerPage, UUID> {

    Page<CrawlerPage> findByStatus(CrawlerPageStatus status, Pageable pageable);

    long countByStatus(CrawlerPageStatus status);

    List<CrawlerPage> findByStatus(CrawlerPageStatus status);

    /** Bulk-approve all pending pages at once. */
    @Modifying
    @Query("""
            UPDATE CrawlerPage p
               SET p.status     = dev.thanh.spring_ai.enums.CrawlerPageStatus.APPROVED,
                   p.reviewedAt = :now,
                   p.reviewedBy = :reviewerId
             WHERE p.status = dev.thanh.spring_ai.enums.CrawlerPageStatus.PENDING
            """)
    int approveAllPending(@Param("now") LocalDateTime now, @Param("reviewerId") UUID reviewerId);
}
