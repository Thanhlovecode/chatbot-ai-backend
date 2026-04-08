package dev.thanh.spring_ai.scheduler;

import dev.thanh.spring_ai.config.RedisStreamProperties;
import dev.thanh.spring_ai.service.PendingMessageRecoveryService;
import dev.thanh.spring_ai.service.RedisStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;


@Slf4j(topic = "SCHEDULER-STREAM-CONSUMER")
@Component
@RequiredArgsConstructor
public class StreamConsumerScheduler {

    private final RedisStreamService streamConsumer;
    private final PendingMessageRecoveryService recoveryService;
    private final RedisStreamProperties streamProperties;
    private final Executor virtualThreadExecutor;

    private final AtomicBoolean consuming = new AtomicBoolean(false);
    private final AtomicBoolean recovering = new AtomicBoolean(false);

    @Scheduled(fixedRateString = "#{@redisStreamProperties.schedulerIntervalMs}")
    public void consumeMessages() {
        if (!consuming.compareAndSet(false, true)) {
            log.debug("Consumption cycle skipped - previous still running");
            return;
        }

        try {
            int concurrency = streamProperties.getConcurrency();
            List<CompletableFuture<Integer>> futures = new ArrayList<>(concurrency);

            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                futures.add(
                        CompletableFuture
                                .supplyAsync(() -> streamConsumer.consumeNewMessages(index), virtualThreadExecutor)
                                .exceptionally(ex -> {
                                    log.error("Consumer-{} failed: {}", index, ex.getMessage(), ex);
                                    return 0;
                                })
                );
            }

            // .join() safe: timeout managed by Redis blockDurationMs, HikariCP connectionTimeout, PG statementTimeout
            int total = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(v -> futures.stream().mapToInt(CompletableFuture::join).sum())
                    .join();

            if (total > 0) {
                log.info("Processed {} messages across {} consumers", total, concurrency);

                // Trim stream sau mỗi vòng consume để ngăn tích lũy vô hạn.
                // Đặt ở scheduler (single-thread) thay vì processMessageBatch (parallel)
                // để tránh race condition. approximateTrimming = true → chi phí CPU ≈ 0.
                streamConsumer.trimStream(50_000);
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
            log.debug("Recovery cycle skipped - previous still running");
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