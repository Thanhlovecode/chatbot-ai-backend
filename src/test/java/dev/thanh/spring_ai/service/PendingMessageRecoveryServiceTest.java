package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RedisStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PendingMessageRecoveryService unit tests — focuses on:
 * 1. Stale message detection (idle time ≥ threshold → recover)
 * 2. Claim + process pipeline (claim → processMessageBatch → count)
 * 3. Exception resilience (never crash scheduler)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingMessageRecoveryService — Unit Tests")
class PendingMessageRecoveryServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisStreamService streamConsumer;
    @Mock private RedisStreamProperties props;
    @Mock private StreamOperations<String, Object, Object> streamOps;

    private PendingMessageRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PendingMessageRecoveryService(redisTemplate, streamConsumer, props);
    }

    @Test
    @DisplayName("no pending messages — should return 0")
    @SuppressWarnings("unchecked")
    void noPending_ShouldReturnZero() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(props.getName()).thenReturn("chat:messages");
        when(props.getConsumerGroup()).thenReturn("chat-processor-group");
        PendingMessages emptyPending = mock(PendingMessages.class);
        when(emptyPending.isEmpty()).thenReturn(true);
        when(streamOps.pending(anyString(), anyString(), any(Range.class), anyLong()))
                .thenReturn(emptyPending);

        int recovered = service.recoverPendingMessages();

        assertThat(recovered).isZero();
        verifyNoInteractions(streamConsumer);
    }

    @Test
    @DisplayName("stale message — should claim, process, and return 1")
    @SuppressWarnings("unchecked")
    void staleMessage_ShouldClaimAndProcess() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(props.getName()).thenReturn("chat:messages");
        when(props.getConsumerGroup()).thenReturn("chat-processor-group");
        when(props.getConsumerName()).thenReturn("worker-1");
        when(props.getPendingIdleThresholdMs()).thenReturn(300000L);
        when(props.getClaimMinIdleTimeMs()).thenReturn(300000L);

        // Build a stale pending message (idle > threshold)
        PendingMessage pendingMsg = mock(PendingMessage.class);
        when(pendingMsg.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(10)); // 600000ms > 300000ms
        when(pendingMsg.getIdAsString()).thenReturn("1711350000000-0");
        when(pendingMsg.getConsumerName()).thenReturn("dead-worker");
        when(pendingMsg.getTotalDeliveryCount()).thenReturn(2L);

        PendingMessages pending = mock(PendingMessages.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(pendingMsg).iterator());

        when(streamOps.pending(anyString(), anyString(), any(Range.class), anyLong()))
                .thenReturn(pending);

        // Claim succeeds
        Map<Object, Object> payload = new HashMap<>();
        payload.put("content", "Hello");
        MapRecord<String, Object, Object> claimed = StreamRecords.mapBacked(payload)
                .withStreamKey("chat:messages")
                .withId(RecordId.of("1711350000000-0"));
        when(streamOps.claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId.class)))
                .thenReturn(List.of(claimed));

        // Process succeeds
        when(streamConsumer.processMessageBatch(anyList())).thenReturn(1);

        int recovered = service.recoverPendingMessages();

        assertThat(recovered).isEqualTo(1);
        verify(streamConsumer).processMessageBatch(anyList());
    }

    @Test
    @DisplayName("non-stale message (idle < threshold) — should skip, return 0")
    @SuppressWarnings("unchecked")
    void nonStaleMessage_ShouldSkip() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(props.getName()).thenReturn("chat:messages");
        when(props.getConsumerGroup()).thenReturn("chat-processor-group");
        when(props.getPendingIdleThresholdMs()).thenReturn(300000L);

        // Message idle for only 1 minute (< 5 min threshold)
        PendingMessage pendingMsg = mock(PendingMessage.class);
        when(pendingMsg.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(1));

        PendingMessages pending = mock(PendingMessages.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(List.of(pendingMsg).iterator());

        when(streamOps.pending(anyString(), anyString(), any(Range.class), anyLong()))
                .thenReturn(pending);

        int recovered = service.recoverPendingMessages();

        assertThat(recovered).isZero();
        verifyNoInteractions(streamConsumer);
    }

    @Test
    @DisplayName("🔴 exception during recovery — should return 0, never crash scheduler")
    @SuppressWarnings("unchecked")
    void exception_ShouldReturnZero() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(props.getName()).thenReturn("chat:messages");
        when(props.getConsumerGroup()).thenReturn("chat-processor-group");
        when(streamOps.pending(anyString(), anyString(), any(Range.class), anyLong()))
                .thenThrow(new RuntimeException("Redis cluster failover"));

        int recovered = service.recoverPendingMessages();

        assertThat(recovered).isZero();
    }
}
