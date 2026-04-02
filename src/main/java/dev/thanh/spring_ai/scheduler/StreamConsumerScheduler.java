package dev.thanh.spring_ai.scheduler;

import dev.thanh.spring_ai.service.PendingMessageRecoveryService;
import dev.thanh.spring_ai.service.RedisStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;


@Slf4j(topic = "SCHEDULER-STREAM-CONSUMER")
@Component
@RequiredArgsConstructor
public class StreamConsumerScheduler {

    private final RedisStreamService streamConsumer;
    private final PendingMessageRecoveryService recoveryService;

    private final AtomicBoolean consuming = new AtomicBoolean(false);
    private final AtomicBoolean recovering = new AtomicBoolean(false);

    @Scheduled(fixedRateString = "#{@redisStreamProperties.schedulerIntervalMs}")
    public void consumeMessages() {
        if (!consuming.compareAndSet(false, true)) {
            log.debug("Consumption cycle skipped - previous still running");
            return;
        }

        try {
            int processed = streamConsumer.consumeNewMessages();

            if (processed > 0) {
                log.info("Processed {} messages", processed);
            }

        } catch (Exception e) {
            log.error("Consumption error: {}", e.getMessage(), e);
        } finally {
            consuming.set(false);
        }
    }

    @Scheduled(fixedRateString = "#{@redisStreamProperties.pendingCheckIntervalMs}")
    public void recoverPendingMessages() {
        if (!recovering.compareAndSet(false, true)) {
            log.info("Recovery cycle skipped - previous still running");
            return;
        }

        try {
            int recovered = recoveryService.recoverPendingMessages();

            if (recovered > 0) {
                log.info("Recovered {} pending messages", recovered);
            }

        } catch (Exception e) {
            log.error("Recovery error: {}", e.getMessage(), e);
        } finally {
            recovering.set(false);
        }
    }
}