package dev.thanh.spring_ai.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized Prometheus metrics for the Chat pipeline.
 * <p>
 * Tất cả custom metrics được register ở đây để dễ quản lý
 * và tránh duplicate registration across services.
 */
@Service
public class ChatMetricsService {

    // ── Gauges ────────────────────────────────────────────────────────────
    @Getter
    private final AtomicInteger activeStreams;

    @Getter
    private final AtomicInteger pendingMessages;

    // ── Counters ──────────────────────────────────────────────────────────
    private final Counter totalRequests;
    private final Counter streamErrors;
    private final Counter redisStreamPushed;

    // ── Timers ─────────────────────────────── ─────────────────────────────
    private final Timer streamDuration;
    private final Timer batchInsertDuration;

    public ChatMetricsService(MeterRegistry registry) {
        // Gauge — số SSE stream đang mở tại thời điểm hiện tại
        this.activeStreams = registry.gauge("chat_streams_active", new AtomicInteger(0));

        // Gauge — số pending messages trong Redis Stream
        this.pendingMessages = registry.gauge("redis_stream_pending", new AtomicInteger(0));

        // Counter — tổng số chat request
        this.totalRequests = Counter.builder("chat_requests_total")
                .description("Total chat stream requests")
                .register(registry);

        // Counter — tổng lỗi stream
        this.streamErrors = Counter.builder("chat_stream_errors_total")
                .description("Total chat stream errors")
                .register(registry);

        // Counter — messages pushed vào Redis Stream
        this.redisStreamPushed = Counter.builder("redis_stream_pushed_total")
                .description("Total messages pushed to Redis Stream")
                .register(registry);

        // Timer — thời gian mỗi stream (từ subscribe → complete/error)
        this.streamDuration = Timer.builder("chat_stream_duration_seconds")
                .description("Duration of chat stream responses")
                .publishPercentileHistogram()
                .register(registry);

        // Timer — thời gian batch insert vào PostgreSQL
        this.batchInsertDuration = Timer.builder("redis_batch_insert_seconds")
                .description("Duration of batch insert operations")
                .publishPercentileHistogram()
                .register(registry);
    }

    // ── Convenience methods ───────────────────────────────────────────────

    public void incrementTotalRequests() {
        totalRequests.increment();
    }

    public void incrementStreamErrors() {
        streamErrors.increment();
    }

    public void incrementRedisStreamPushed() {
        redisStreamPushed.increment();
    }

    public Timer.Sample startStreamTimer() {
        return Timer.start();
    }

    public void stopStreamTimer(Timer.Sample sample) {
        sample.stop(streamDuration);
    }

    public Timer.Sample startBatchInsertTimer() {
        return Timer.start();
    }

    public void stopBatchInsertTimer(Timer.Sample sample) {
        sample.stop(batchInsertDuration);
    }
}
