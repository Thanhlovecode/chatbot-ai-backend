package dev.thanh.spring_ai.scheduler;

import dev.thanh.spring_ai.service.JobRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CrawlJobScheduler {

    private final JobRegistryService jobRegistryService;

    // Runs every day at 6:00 AM (server time)
    @Scheduled(cron = "${crawler.cron-schedule:0 0 6 * * *}")
    public void dailyCrawl() {
        log.info("Triggering scheduled daily crawl job");
        try {
            jobRegistryService.triggerJob(JobRegistryService.JOB_CRAWL_ALL);
        } catch (IllegalStateException e) {
            log.warn("Scheduled job skipped: {}", e.getMessage());
        }
    }
}
