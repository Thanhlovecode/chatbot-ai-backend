package dev.thanh.spring_ai.dto.response.admin;

import java.time.LocalDate;

public record CrawlHistoryDto(
        LocalDate date,
        int successCount,
        int failCount
) {
}
