package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.dto.response.admin.BackgroundJobDto;
import dev.thanh.spring_ai.dto.response.admin.JobListResponse;
import dev.thanh.spring_ai.dto.response.admin.JobStatsDto;
import dev.thanh.spring_ai.entity.CrawlSource;
import dev.thanh.spring_ai.entity.JobExecution;
import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import dev.thanh.spring_ai.enums.JobStatus;
import dev.thanh.spring_ai.enums.JobType;
import dev.thanh.spring_ai.repository.CrawlSourceRepository;
import dev.thanh.spring_ai.repository.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobRegistryService {

    private final JobExecutionRepository jobExecutionRepository;
    private final CrawlSourceRepository crawlSourceRepository;
    private final CrawlerService crawlerService;

    // In-memory registry to track running status to prevent concurrent runs
    private final Map<String, AtomicBoolean> jobRunningLocks = new ConcurrentHashMap<>();

    // Logical jobs
    public static final String JOB_CRAWL_ALL = "crawl-all";

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void init() {
        jobRunningLocks.put(JOB_CRAWL_ALL, new AtomicBoolean(false));

        // Recover stale RUNNING records left by a previous crash/restart
        List<JobExecution> staleJobs = jobExecutionRepository.findByStatus(JobStatus.RUNNING);
        if (!staleJobs.isEmpty()) {
            log.warn("Recovering {} stale RUNNING job(s) from previous run", staleJobs.size());
            staleJobs.forEach(job -> {
                job.setStatus(JobStatus.FAILED);
                job.setErrorMsg("Server restarted while job was running");
                job.setFinishedAt(LocalDateTime.now());
            });
            jobExecutionRepository.saveAll(staleJobs);
        }
    }

    public JobListResponse listJobs() {
        // 1. Calculate Stats
        long totalJobs = 1; // Initially just "crawl-all" logic
        long runningJobs = jobRunningLocks.values().stream().filter(AtomicBoolean::get).count();
        
        LocalDateTime today = LocalDate.now().atStartOfDay();
        long successToday = jobExecutionRepository.countSuccessSince(today);
        long failedToday = jobExecutionRepository.countFailedSince(today);
        
        JobStatsDto stats = JobStatsDto.builder()
                .totalJobs(totalJobs)
                .runningJobs(runningJobs)
                .successfulJobsToday(successToday)
                .failedJobsToday(failedToday)
                .build();
                
        // 2. Build Job list
        List<BackgroundJobDto> jobs = new ArrayList<>();
        
        // --- CRAWL ALL JOB ---
        boolean isCrawlAllRunning = jobRunningLocks.get(JOB_CRAWL_ALL).get();
        JobExecution lastCrawlAll = jobExecutionRepository.findTopByJobIdOrderByStartedAtDesc(JOB_CRAWL_ALL).orElse(null);
        
        JobStatus status = isCrawlAllRunning ? JobStatus.RUNNING :
                (lastCrawlAll != null ? lastCrawlAll.getStatus() : JobStatus.IDLE);
                
        jobs.add(BackgroundJobDto.builder()
                .jobId(JOB_CRAWL_ALL)
                .jobName("Crawl All Active Sources")
                .jobType(JobType.SCHEDULED)
                .cronSchedule("0 0 6 * * *")
                .lastRunAt(lastCrawlAll != null ? lastCrawlAll.getStartedAt() : null)
                .lastDurationMs(lastCrawlAll != null ? lastCrawlAll.getDurationMs() : null)
                .status(status)
                .build());
                
        return JobListResponse.builder()
                .stats(stats)
                .jobs(jobs)
                .build();
    }

    public synchronized void triggerJob(String jobId) {
        if (!jobRunningLocks.containsKey(jobId)) {
            throw new IllegalArgumentException("Unknown job ID: " + jobId);
        }
        
        if (!jobRunningLocks.get(jobId).compareAndSet(false, true)) {
            throw new IllegalStateException("Job is already running");
        }
        
        if (JOB_CRAWL_ALL.equals(jobId)) {
            triggerCrawlAllAsync();
        }
    }

    private void triggerCrawlAllAsync() {
        // Run async in a new virtual thread to avoid blocking the request thread
        Thread.ofVirtual().start(() -> {
            log.info("Starting background job: {}", JOB_CRAWL_ALL);
            JobExecution execution = JobExecution.builder()
                    .jobId(JOB_CRAWL_ALL)
                    .jobName("Crawl All Active Sources")
                    .jobType(JobType.MANUAL)
                    .status(JobStatus.RUNNING)
                    .build();
            execution = jobExecutionRepository.save(execution);

            try {
                // Find all active sources and crawl them sequentially to track total pages
                List<CrawlSource> activeSources = crawlSourceRepository.findByStatus(CrawlSourceStatus.ACTIVE);
                int totalPages = 0;

                for (CrawlSource source : activeSources) {
                    try {
                        int pages = crawlerService.crawl(source);
                        totalPages += pages;
                        source.setLastCrawlAt(LocalDateTime.now());
                        source.setPagesCrawled(source.getPagesCrawled() + pages);
                        crawlSourceRepository.save(source);
                    } catch (Exception sourceEx) {
                        log.error("Failed to crawl source '{}', skipping: {}", source.getName(), sourceEx.getMessage());
                        // Continue crawling remaining sources
                    }
                }

                execution.setStatus(JobStatus.SUCCESS);
                execution.setPagesCount(totalPages);
                execution.setFinishedAt(LocalDateTime.now());
                jobExecutionRepository.save(execution);
            } catch (Exception e) {
                log.error("Failed executing job: {}", JOB_CRAWL_ALL, e);
                execution.setStatus(JobStatus.FAILED);
                execution.setErrorMsg(e.getMessage());
                execution.setFinishedAt(LocalDateTime.now());
                jobExecutionRepository.save(execution);
            } finally {
                // VERY IMPORTANT: Release the lock
                jobRunningLocks.get(JOB_CRAWL_ALL).set(false);
            }
        });
    }
}
