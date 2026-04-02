package dev.thanh.spring_ai.service;



import dev.thanh.spring_ai.config.RedisStreamProperties;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.dto.request.StreamMessageMetadata;
import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.repository.BatchMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStreamService {


    private static final int LIMIT_SIZE = 20;
    private static final String HISTORY_PREFIX = "chat:history:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisStreamProperties streamProperties;
    private final MessageProcessorService messageProcessor;
    private final DeadLetterQueueService dlqService;
    private final BatchMessageRepository batchRepository;


    public void pushToStream(MessageDTO messageInfo) {
        Map<String, String> messageData = new HashMap<>();
        messageData.put("type", messageInfo.getRole().name());
        messageData.put("id", messageInfo.getId().toString());
        messageData.put("sessionId", messageInfo.getSessionId());
        messageData.put("content", messageInfo.getContent());
        messageData.put("createdAt", messageInfo.getCreatedAt().toString());

        StringRecord record = StreamRecords.string(messageData).withStreamKey(streamProperties.getName());
        RecordId recordId = redisTemplate.opsForStream().add(record);

        log.info("Pushed to stream: type={}, sessionId={}, recordId={}", messageInfo.getRole().name(),messageInfo.getSessionId(), recordId);
    }

    public List<MessageDTO> getHistory(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        try {
            List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);

            if (rawHistory == null || rawHistory.isEmpty()) {
                log.info("No history found for session {}", sessionId);
                return List.of();
            }

            List<MessageDTO> history = rawHistory.stream()
                    .map(obj -> (MessageDTO) obj)
                    .toList();

            log.info("Retrieved history for session {}: {} messages", sessionId, history.size());
            return history;
        } catch (Exception e) {
            log.error("Failed to load history for session {} from Redis: {}. Clearing corrupted cache.", sessionId, e.getMessage());
            redisTemplate.delete(key);
            return List.of();
        }
    }



    public void trimStream(long maxLength) {
        redisTemplate.opsForStream().trim(streamProperties.getName(), maxLength, true);
        log.info("Trimmed stream to max {} messages", maxLength);
    }

    public boolean hasHistory(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        Long size = redisTemplate.opsForList().size(key);
        return size != null && size > 0;
    }

    public void updateHistoryCachePipeline(MessageDTO userMessage, MessageDTO assistantMessage) {
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(@NonNull RedisOperations<K, V> operations) throws DataAccessException {
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;
                    String sessionKey = HISTORY_PREFIX + userMessage.getSessionId();

                    ops.opsForList().rightPush(sessionKey, userMessage);
                    ops.opsForList().rightPush(sessionKey, assistantMessage);
                    ops.opsForList().trim(sessionKey, -LIMIT_SIZE, -1);
                    ops.expire(sessionKey, Duration.ofHours(24));

                    return null;
                }
            });

        } catch (Exception e) {
            log.warn("⚠️ Cache update failed for session {} - messages are in stream",
                    userMessage.getSessionId(), e);
        }
    }

    public void cacheHistory(String sessionId, List<MessageDTO> messages) {
        String key = HISTORY_PREFIX + sessionId;

        if (messages != null && !messages.isEmpty()) {
            // Push tất cả MessageDTO vào List
            redisTemplate.opsForList().rightPushAll(key, messages.toArray());
            redisTemplate.expire(key, Duration.ofHours(24));
            log.info("Cached {} messages for session {}", messages.size(), sessionId);
        }
    }

    /**
     * Clear the history cache for a specific session.
     * Called when a session is deleted.
     */
    public void clearHistory(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        Boolean deleted = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("Cleared history cache for session {}", sessionId);
        }
    }


    @SuppressWarnings("unchecked")
    public int consumeNewMessages() {
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(
                            streamProperties.getConsumerGroup(),
                            streamProperties.getConsumerName()
                    ),
                    StreamReadOptions.empty()
                            .count(streamProperties.getBatchSize())
                            .block(Duration.ofMillis(streamProperties.getBlockDurationMs())),
                    StreamOffset.create(streamProperties.getName(), ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return 0;
            }

            log.info("Read {} new messages from stream", records.size());
            return processMessageBatch(records);

        } catch (Exception e) {
            log.error("Error reading new messages from stream", e);
            return 0;
        }
    }

    public int processMessageBatch(List<MapRecord<String, Object, Object>> records) {
        try {
            // Phase 1: Transform stream entries to domain entities
            List<ChatMessage> messages = records.stream()
                    .map(messageProcessor::transformToChatMessage)
                    .filter(Objects::nonNull)
                    .toList();

            if (messages.isEmpty()) {
                log.info("No valid messages after transformation");
                return 0;
            }

            // Phase 2: Batch insert to PostgreSQL
            int insertedCount = batchRepository.batchInsert(messages);

            List<String> messageIds = records.stream()
                    .map(record -> record.getId().getValue())
                    .collect(Collectors.toList());

            acknowledgeMessages(messageIds);

            log.info("Successfully processed batch: {} messages", insertedCount);
            return insertedCount;

        } catch (Exception e) {
            log.error("Batch insert failed, falling back to single insert for {} messages", records.size(), e);
            return handleFailedBatch(records);
        }
    }

    private void acknowledgeMessages(List<String> messageIds) {
        if (messageIds.isEmpty()) return;
        try {
            Long ackedCount = redisTemplate.opsForStream().acknowledge(
                    streamProperties.getName(),
                    streamProperties.getConsumerGroup(),
                    messageIds.toArray(new String[0])
            );
            log.debug("Acknowledged {} messages", ackedCount);
        } catch (Exception e) {
            log.error("Failed to acknowledge messages (non-critical, will retry)", e);
        }
    }

    private int handleFailedBatch(List<MapRecord<String, Object, Object>> records) {
        List<String> succeededIds = new ArrayList<>();
        List<String> dlqIds = new ArrayList<>();
        int insertedCount = 0;

        for (MapRecord<String, Object, Object> record : records) {
            String messageId = record.getId().getValue();
            try {
                ChatMessage message = messageProcessor.transformToChatMessage(record);
                if (message != null && batchRepository.singleInsert(message)) {
                    insertedCount++;
                }
                succeededIds.add(messageId);
            } catch (Exception singleError) {
                log.error("Single insert failed for message {}", messageId, singleError);
                StreamMessageMetadata metadata = getSpecificMessageMetadata(messageId);
                if (metadata != null && metadata.getDeliveryCount() >= streamProperties.getMaxRetryAttempts()) {
                    log.warn("Message {} exceeded max retries, moving to DLQ", messageId);
                    dlqService.sendToDeadLetterQueue(record, singleError);
                    dlqIds.add(messageId);
                }
                // else: không acknowledge → Redis Stream sẽ tự redeliver
            }
        }

        // Acknowledge tất cả 1 lần duy nhất
        List<String> allAckIds = new ArrayList<>(succeededIds);
        allAckIds.addAll(dlqIds);
        acknowledgeMessages(allAckIds);

        log.info("Fallback result: inserted={}, dlq={}, pendingRetry={}",
                insertedCount, dlqIds.size(), records.size() - succeededIds.size() - dlqIds.size());
        return insertedCount;
    }

    private StreamMessageMetadata getSpecificMessageMetadata(String messageId) {
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    streamProperties.getName(),
                    Consumer.from(streamProperties.getConsumerGroup(), streamProperties.getConsumerName()),
                    Range.closed(messageId, messageId),
                    1L
            );

            if (pending != null && !pending.isEmpty()) {
                PendingMessage msg = pending.get(0);
                return StreamMessageMetadata.builder()
                        .messageId(msg.getIdAsString())
                        .deliveryCount((int) msg.getTotalDeliveryCount())
                        .build();
            }
        } catch (Exception e) {
            log.error("Error fetching metadata for {}", messageId, e);
        }
        return null;
    }

}