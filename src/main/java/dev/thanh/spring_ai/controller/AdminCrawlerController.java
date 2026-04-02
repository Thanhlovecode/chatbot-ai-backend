package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.dto.request.admin.AddCrawlSourceRequest;
import dev.thanh.spring_ai.dto.request.admin.UpdateCrawlSourceRequest;
import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.dto.response.admin.CrawlSourceDto;
import dev.thanh.spring_ai.dto.response.admin.CrawlerPageDto;
import dev.thanh.spring_ai.service.AdminCrawlerService;
import dev.thanh.spring_ai.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/crawler")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
@RequiredArgsConstructor
public class AdminCrawlerController {

    private final AdminCrawlerService adminCrawlerService;

    // ─── Pages ─────────────────────────────────────────────────────────────────

    @GetMapping("/pages")
    public ResponseEntity<ResponseData<Page<CrawlerPageDto>>> listPages(
            @RequestParam(defaultValue = "ALL") String status,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<CrawlerPageDto> data = adminCrawlerService.listPages(status, pageable);
        return ok("Fetched crawler pages successfully", data);
    }

    @PostMapping("/pages/{id}/approve")
    public ResponseEntity<ResponseData<Void>> approvePage(@PathVariable UUID id) {
        adminCrawlerService.approvePage(id, SecurityUtils.getCurrentUserId());
        return ok("Page approved successfully");
    }

    @PostMapping("/pages/{id}/reject")
    public ResponseEntity<ResponseData<Void>> rejectPage(@PathVariable UUID id) {
        adminCrawlerService.rejectPage(id, SecurityUtils.getCurrentUserId());
        return ok("Page rejected successfully");
    }

    @PostMapping("/pages/approve-all")
    public ResponseEntity<ResponseData<Void>> approveAllPending() {
        adminCrawlerService.approveAllPending(SecurityUtils.getCurrentUserId());
        return ok("All pending pages approved successfully");
    }

    // ─── Sources ───────────────────────────────────────────────────────────────

    @GetMapping("/sources")
    public ResponseEntity<ResponseData<List<CrawlSourceDto>>> listSources() {
        return ok("Fetched crawl sources successfully", adminCrawlerService.listSources());
    }

    @PostMapping("/sources")
    public ResponseEntity<ResponseData<CrawlSourceDto>> addSource(
            @Valid @RequestBody AddCrawlSourceRequest request) {
        return ok("Crawl source added successfully", adminCrawlerService.addSource(request));
    }

    @PutMapping("/sources/{id}")
    public ResponseEntity<ResponseData<CrawlSourceDto>> updateSource(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCrawlSourceRequest request) {
        return ok("Crawl source updated successfully", adminCrawlerService.updateSource(id, request));
    }

    @PostMapping("/sources/{id}/crawl-now")
    public ResponseEntity<ResponseData<Void>> triggerCrawl(@PathVariable UUID id) {
        adminCrawlerService.triggerCrawl(id);
        return ok("Crawl job triggered successfully");
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private <T> ResponseEntity<ResponseData<T>> ok(String message, T data) {
        return ResponseEntity.ok(ResponseData.<T>builder()
                .status(200)
                .message(message)
                .timestamp(ZonedDateTime.now())
                .data(data)
                .build());
    }

    private ResponseEntity<ResponseData<Void>> ok(String message) {
        return ok(message, null);
    }
}
