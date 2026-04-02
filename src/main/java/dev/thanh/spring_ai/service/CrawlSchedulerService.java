package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.entity.CrawlSource;
import dev.thanh.spring_ai.entity.JobExecution;
import dev.thanh.spring_ai.enums.JobStatus;
import dev.thanh.spring_ai.enums.JobType;
import dev.thanh.spring_ai.repository.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlSchedulerService {

    private final CrawlerService crawlerService;
    private final JobExecutionRepository jobExecutionRepository;

    @Async
    public void triggerCrawl(CrawlSource source, JobType jobType) {
        // Create RUNNING job execution record
        LocalDateTime start = LocalDateTime.now();
        JobExecution jobExecution = JobExecution.builder()
                .jobId("crawl-source-" + source.getId())
                .jobName("Crawl: " + source.getName())
                .jobType(jobType)
                .status(JobStatus.RUNNING)
                .startedAt(start)
                .build();
        jobExecution = jobExecutionRepository.save(jobExecution);

        try {
            int pagesFound = crawlerService.crawl(source);
            
            LocalDateTime finish = LocalDateTime.now();
            long duration = Duration.between(start, finish).toMillis();

            jobExecution.setStatus(JobStatus.SUCCESS);
            jobExecution.setPagesCount(pagesFound);
            jobExecution.setDurationMs(duration);
            jobExecution.setFinishedAt(finish);
            jobExecutionRepository.save(jobExecution);
            
            log.info("Finished {} crawl job for source {}: {} pages, {} ms", 
                    jobType, source.getName(), pagesFound, duration);
                    
        } catch (Exception e) {
            log.error("Crawl job failed for source: {}", source.getName(), e);
            
            LocalDateTime finish = LocalDateTime.now();
            jobExecution.setStatus(JobStatus.FAILED);
            jobExecution.setErrorMsg(e.getMessage());
            jobExecution.setDurationMs(Duration.between(start, finish).toMillis());
            jobExecution.setFinishedAt(finish);
            jobExecutionRepository.save(jobExecution);
        }
    }
}
