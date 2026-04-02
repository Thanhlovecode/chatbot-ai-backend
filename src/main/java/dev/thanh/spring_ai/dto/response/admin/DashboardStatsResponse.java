package dev.thanh.spring_ai.dto.response.admin;

import java.time.LocalDateTime;

public record DashboardStatsResponse(
        long activeDocuments,
        long qdrantVectorCount,
        long pendingPages,
        LastCrawlInfo lastCrawl,
        long todayQueries,
        double lowRelevancePercent
) {
    public record LastCrawlInfo(
            Integer pagesCount,
            LocalDateTime crawledAt
    ) {
    }
}
