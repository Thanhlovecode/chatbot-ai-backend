package dev.thanh.spring_ai.service;


import dev.thanh.spring_ai.config.RedisStreamProperties;
import io.micrometer.core.annotation.Counted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterQueueService {

    private final StringRedisTemplate redisTemplate;
    private final RedisStreamProperties streamProperties;

    @Counted(value = "stream.messages.dlq", description = "Messages sent to DLQ")
    public void sendToDeadLetterQueue(MapRecord<String, Object, Object> record, Throwable error) {
        try {
            redisTemplate.opsForStream().add(
                    StreamRecords.objectBacked(buildDlqEntry(record, error))
                            .withStreamKey(streamProperties.getDeadLetterStream())
            );

            log.warn("Message {} moved to DLQ: {}",
                    record.getId().getValue(), error.getMessage());

        } catch (Exception e) {
            log.error("CRITICAL: Failed to write message {} to DLQ",
                    record.getId().getValue(), e);
        }
    }

    private Map<String, Object> buildDlqEntry(MapRecord<String, Object, Object> record, Throwable error) {
        return Map.of(
                "originalMessageId", record.getId().getValue(),
                "originalStream", Objects.requireNonNull(record.getStream()),
                "originalPayload", record.getValue(),
                "errorMessage", error.getMessage(),
                "errorClass", error.getClass().getSimpleName(),
                "failedAt", Instant.now().toString()
        );
    }
}