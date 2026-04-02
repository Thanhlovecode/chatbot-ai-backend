package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.dto.request.admin.AddCrawlSourceRequest;
import dev.thanh.spring_ai.dto.request.admin.UpdateCrawlSourceRequest;
import dev.thanh.spring_ai.dto.response.admin.CrawlSourceDto;
import dev.thanh.spring_ai.dto.response.admin.CrawlerPageDto;
import dev.thanh.spring_ai.entity.CrawlSource;
import dev.thanh.spring_ai.entity.CrawlerPage;
import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import dev.thanh.spring_ai.enums.CrawlerPageStatus;
import dev.thanh.spring_ai.enums.JobType;
import dev.thanh.spring_ai.exception.BadRequestException;
import dev.thanh.spring_ai.exception.ResourceNotFoundException;
import dev.thanh.spring_ai.repository.CrawlSourceRepository;
import dev.thanh.spring_ai.repository.CrawlerPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service handling all admin operations for the Crawler tab of the RAG dashboard.
 * Controllers delegate here — no business logic should live in the Controller layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCrawlerService {

    private final CrawlerPageRepository crawlerPageRepository;
    private final CrawlSourceRepository crawlSourceRepository;
    private final CrawlerService crawlerService;
    private final CrawlSchedulerService crawlSchedulerService;

    // ─── Pages ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CrawlerPageDto> listPages(String status, Pageable pageable) {
        Page<CrawlerPage> page;
        if ("ALL".equalsIgnoreCase(status)) {
            page = crawlerPageRepository.findAll(pageable);
        } else {
            CrawlerPageStatus pageStatus;
            try {
                pageStatus = CrawlerPageStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid status value: '" + status
                        + "'. Allowed values: ALL, " + java.util.Arrays.toString(CrawlerPageStatus.values()));
            }
            page = crawlerPageRepository.findByStatus(pageStatus, pageable);
        }

        return page.map(p -> CrawlerPageDto.builder()
                .id(p.getId())
                .sourceId(p.getSourceId())
                .title(p.getTitle())
                .url(p.getUrl())
                .topicTag(p.getTopicTag())
                .wordCount(p.getWordCount())
                .chunkCount(p.getChunkCount())
                .status(p.getStatus())
                .crawledAt(p.getCrawledAt())
                .build());
    }

    @Transactional
    public void approvePage(UUID id, UUID userId) {
        crawlerService.approvePage(id, userId);
    }

    @Transactional
    public void rejectPage(UUID id, UUID userId) {
        crawlerService.rejectPage(id, userId);
    }

    @Transactional
    public void approveAllPending(UUID userId) {
        crawlerService.approveAllPending(userId);
    }

    // ─── Sources ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CrawlSourceDto> listSources() {
        return crawlSourceRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public CrawlSourceDto addSource(AddCrawlSourceRequest request) {
        CrawlSource source = CrawlSource.builder()
                .name(request.getName())
                .baseUrl(request.getBaseUrl())
                .cronSchedule(request.getCronSchedule())
                .maxDepth(request.getMaxDepth())
                .status(CrawlSourceStatus.ACTIVE)
                .pagesCrawled(0)
                .build();

        source = crawlSourceRepository.save(source);
        log.info("Added new crawl source: {} ({})", source.getName(), source.getBaseUrl());
        return toDto(source);
    }

    @Transactional
    public CrawlSourceDto updateSource(UUID id, UpdateCrawlSourceRequest request) {
        CrawlSource source = crawlSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CrawlSource", "id", id));

        source.setName(request.getName());
        source.setBaseUrl(request.getBaseUrl());

        if (request.getCronSchedule() != null) {
            source.setCronSchedule(request.getCronSchedule());
        }
        if (request.getMaxDepth() != null) {
            source.setMaxDepth(request.getMaxDepth());
        }
        if (request.getStatus() != null) {
            source.setStatus(request.getStatus());
        }

        source = crawlSourceRepository.save(source);
        log.info("Updated crawl source: {}", id);
        return toDto(source);
    }

    public void triggerCrawl(UUID id) {
        CrawlSource source = crawlSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CrawlSource", "id", id));
        crawlSchedulerService.triggerCrawl(source, JobType.MANUAL);
        log.info("Triggered manual crawl for source: {}", id);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private CrawlSourceDto toDto(CrawlSource s) {
        return CrawlSourceDto.builder()
                .id(s.getId())
                .name(s.getName())
                .baseUrl(s.getBaseUrl())
                .cronSchedule(s.getCronSchedule())
                .maxDepth(s.getMaxDepth())
                .status(s.getStatus())
                .pagesCrawled(s.getPagesCrawled())
                .lastCrawlAt(s.getLastCrawlAt())
                .build();
    }
}
