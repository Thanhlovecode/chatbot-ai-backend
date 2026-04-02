package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.CrawlSource;
import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CrawlSourceRepository extends JpaRepository<CrawlSource, UUID> {

    List<CrawlSource> findByStatus(CrawlSourceStatus status);

    boolean existsByBaseUrl(String baseUrl);
}

