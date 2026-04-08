package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.dto.response.admin.CrawlHistoryDto;
import dev.thanh.spring_ai.dto.response.admin.DashboardStatsResponse;
import dev.thanh.spring_ai.dto.response.admin.TopicCoverageDto;
import dev.thanh.spring_ai.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @GetMapping("/stats")
    public ResponseEntity<ResponseData<DashboardStatsResponse>> getStats() {
        DashboardStatsResponse stats = adminDashboardService.getStats();
        return ResponseEntity.ok(
                ResponseData.<DashboardStatsResponse>builder()
                        .status(200)
                        .message("Fetched dashboard stats successfully")
                        .timestamp(ZonedDateTime.now())
                        .data(stats)
                        .build()
        );
    }

    @GetMapping("/topics")
    public ResponseEntity<ResponseData<List<TopicCoverageDto>>> getTopicCoverage() {
        List<TopicCoverageDto> coverage = adminDashboardService.getTopicCoverage();
        return ResponseEntity.ok(
                ResponseData.<List<TopicCoverageDto>>builder()
                        .status(200)
                        .message("Fetched topic coverage successfully")
                        .timestamp(ZonedDateTime.now())
                        .data(coverage)
                        .build()
        );
    }

    @GetMapping("/crawl-history")
    public ResponseEntity<ResponseData<List<CrawlHistoryDto>>> getCrawlHistory() {
        List<CrawlHistoryDto> history = adminDashboardService.getCrawlHistory();
        return ResponseEntity.ok(
                ResponseData.<List<CrawlHistoryDto>>builder()
                        .status(200)
                        .message("Fetched crawl history successfully")
                        .timestamp(ZonedDateTime.now())
                        .data(history)
                        .build()
        );
    }
}
