package dev.thanh.spring_ai.dto.response.admin;

import dev.thanh.spring_ai.enums.CrawlerPageStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class CrawlerPageDto {
    private UUID id;
    private UUID sourceId;
    private String sourceName; // Extracted from source if needed
    private String title;
    private String url;
    private String topicTag;
    private Integer wordCount;
    private Integer chunkCount;
    private CrawlerPageStatus status;
    private LocalDateTime crawledAt;
}
