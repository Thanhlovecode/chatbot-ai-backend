package dev.thanh.spring_ai.dto.response.admin;

import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class CrawlSourceDto {
    private UUID id;
    private String name;
    private String baseUrl;
    private String cronSchedule;
    private Integer maxDepth;
    private CrawlSourceStatus status;
    private Integer pagesCrawled;
    private LocalDateTime lastCrawlAt;
}

