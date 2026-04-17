package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RedisStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DeadLetterQueueService unit tests — focuses on:
 * 1. DLQ entry contains correct diagnostic fields (for debugging failed messages)
 * 2. Redis failure during DLQ write — logs CRITICAL but never crashes
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadLetterQueueService — Unit Tests")
class DeadLetterQueueServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisStreamProperties streamProperties;
    @Mock private StreamOperations<String, Object, Object> streamOps;

    private DeadLetterQueueService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterQueueService(redisTemplate, streamProperties);
    }

    @Test
    @DisplayName("happy path — should write DLQ entry with originalMessageId, errorMessage, failedAt")
    @SuppressWarnings("unchecked")
    void happyPath_ShouldWriteDlqEntry() {
        // Given
        Map<Object, Object> payload = new HashMap<>();
        payload.put("content", "Hello");
        payload.put("sessionId", "session-123");
        MapRecord<String, Object, Object> record = StreamRecords.mapBacked(payload)
                .withStreamKey("chat:messages")
                .withId(RecordId.of("1711350000000-0"));

        RuntimeException error = new RuntimeException("DB connection timeout");

        when(streamProperties.getDeadLetterStream()).thenReturn("chat:messages:dlq");
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(ObjectRecord.class))).thenReturn(RecordId.of("1711350000001-0"));

        // When
        service.sendToDeadLetterQueue(record, error);

        // Then
        ArgumentCaptor<ObjectRecord<String, Map<String, Object>>> captor =
                ArgumentCaptor.forClass(ObjectRecord.class);
        verify(streamOps).add(captor.capture());

        ObjectRecord<String, Map<String, Object>> dlqRecord = captor.getValue();
        assertThat(dlqRecord.getStream()).isEqualTo("chat:messages:dlq");

        Map<String, Object> dlqEntry = dlqRecord.getValue();
        assertThat(dlqEntry.get("originalMessageId")).isEqualTo("1711350000000-0");
        assertThat(dlqEntry.get("originalStream")).isEqualTo("chat:messages");
        assertThat(dlqEntry.get("errorMessage")).isEqualTo("DB connection timeout");
        assertThat(dlqEntry.get("errorClass")).isEqualTo("RuntimeException");
        assertThat(dlqEntry.get("failedAt")).isNotNull();
    }

    @Test
    @DisplayName("🔴 Redis failure during DLQ write — should NOT crash (logs CRITICAL)")
    @SuppressWarnings("unchecked")
    void redisFailure_ShouldNotCrash() {
        // Given
        Map<Object, Object> payload = new HashMap<>();
        payload.put("content", "Hello");
        MapRecord<String, Object, Object> record = StreamRecords.mapBacked(payload)
                .withStreamKey("chat:messages")
                .withId(RecordId.of("1711350000000-0"));

        RuntimeException error = new RuntimeException("Some error");

        when(streamProperties.getDeadLetterStream()).thenReturn("chat:messages:dlq");
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(ObjectRecord.class)))
                .thenThrow(new RuntimeException("Redis OOM"));

        // When / Then: should NOT throw
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.sendToDeadLetterQueue(record, error));
    }
}
