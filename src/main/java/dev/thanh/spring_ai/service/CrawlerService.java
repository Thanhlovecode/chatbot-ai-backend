package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.entity.CrawlSource;
import dev.thanh.spring_ai.entity.CrawlerPage;
import dev.thanh.spring_ai.enums.CrawlerPageStatus;
import dev.thanh.spring_ai.exception.ResourceNotFoundException;
import dev.thanh.spring_ai.repository.CrawlerPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlerService {

    private final CrawlerPageRepository crawlerPageRepository;

    @Transactional
    public int crawl(CrawlSource source) {
        log.info("Starting crawl for source: {} ({})", source.getName(), source.getBaseUrl());

        // TODO: Implement actual Jsoup logic here
        // For now, insert fake pages

        int simulatedPageCount = 3;

        for (int i = 0; i < simulatedPageCount; i++) {
            CrawlerPage page = CrawlerPage.builder()
                    .sourceId(source.getId())
                    .title("Simulated Crawl Page " + (i + 1) + " from " + source.getName())
                    .url(source.getBaseUrl() + "/page/" + (i + 1))
                    .topicTag("simulated")
                    .wordCount(500 + i * 50)
                    .chunkCount(2 + i)
                    .status(CrawlerPageStatus.PENDING)
                    .crawledAt(LocalDateTime.now())
                    .build();
            crawlerPageRepository.save(page);
        }

        log.info("Crawled {} pages from source: {}", simulatedPageCount, source.getName());
        return simulatedPageCount;
    }

    @Transactional
    public void approvePage(UUID pageId, UUID reviewerId) {
        CrawlerPage page = crawlerPageRepository.findById(pageId)
                .orElseThrow(() -> new ResourceNotFoundException("CrawlerPage", "id", pageId));

        page.setStatus(CrawlerPageStatus.APPROVED);
        page.setReviewedAt(LocalDateTime.now());
        page.setReviewedBy(reviewerId);

        // TODO: Async trigger ingestion into Qdrant here by passing the page content
        log.info("Approved page {} for ingestion", pageId);
    }

    @Transactional
    public void rejectPage(UUID pageId, UUID reviewerId) {
        CrawlerPage page = crawlerPageRepository.findById(pageId)
                .orElseThrow(() -> new ResourceNotFoundException("CrawlerPage", "id", pageId));

        page.setStatus(CrawlerPageStatus.REJECTED);
        page.setReviewedAt(LocalDateTime.now());
        page.setReviewedBy(reviewerId);
        log.info("Rejected page {}", pageId);
    }

    @Transactional
    public void approveAllPending(UUID reviewerId) {
        int updated = crawlerPageRepository.approveAllPending(LocalDateTime.now(), reviewerId);
        log.info("Bulk approved {} pending pages", updated);
        // TODO: Trigger async ingestion for all these pages
    }
}
