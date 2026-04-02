package dev.thanh.spring_ai.scheduler;

import dev.thanh.spring_ai.repository.BatchSessionRepository;
import dev.thanh.spring_ai.service.SessionActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j(topic = "SCHEDULER-SESSION-SYNC")
@Component
@RequiredArgsConstructor
public class SessionSyncScheduler {

    private final SessionActivityService sessionActivityService;
    private final BatchSessionRepository batchSessionRepository;

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    @Value("${session.sync.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedRateString = "${session.sync.interval-ms:10000}")
    public void syncDirtySessionsToDb() {
        if (!syncing.compareAndSet(false, true)) {
            log.debug("Session sync skipped — previous cycle still running");
            return;
        }

        try {
            Map<String, LocalDateTime> dirtySessions = sessionActivityService.popDirtySessions(batchSize);

            if (!dirtySessions.isEmpty()) {
                int updated = batchSessionRepository.batchUpdateTimestamps(dirtySessions);
                log.info("Synced {} dirty sessions to DB ({} rows updated)", dirtySessions.size(), updated);
            }
        } catch (Exception e) {
            log.error("Session sync error: {}", e.getMessage(), e);
        } finally {
            syncing.set(false);
        }
    }
}
