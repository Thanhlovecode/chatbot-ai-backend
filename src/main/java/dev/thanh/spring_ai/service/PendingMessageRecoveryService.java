package dev.thanh.spring_ai.service;


import dev.thanh.spring_ai.config.RedisStreamProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class PendingMessageRecoveryService {

    private final StringRedisTemplate redisTemplate;
    private final RedisStreamService streamConsumer;
    private final RedisStreamProperties props;

    /**
     * Recover stale pending messages.
     * Returns number of messages successfully recovered.
     */

    public int recoverPendingMessages() {
        try {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(props.getName(), props.getConsumerGroup(), Range.unbounded(), 100L);

            if (pending == null || pending.isEmpty()) {
                return 0;
            }

            int recovered = 0;

            for (PendingMessage msg : pending) {
                if (isStale(msg) && claimAndProcess(msg)) {
                    recovered++;
                }
            }

            if (recovered > 0) {
                log.info("Recovered {} pending messages", recovered);
            }

            return recovered;

        } catch (Exception e) {
            log.error("Recovery failed", e); // stack trace đầy đủ
            return 0;
        }
    }


    private boolean isStale(PendingMessage msg) {
        return msg.getElapsedTimeSinceLastDelivery().toMillis() >= props.getPendingIdleThresholdMs();
    }

    private boolean claimAndProcess(PendingMessage pending) {
        try {
            String messageId = pending.getIdAsString();
            String recoveryConsumer = props.getConsumerName() + "-recovery"; // tách khỏi active consumer

            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(
                            props.getName(),
                            props.getConsumerGroup(),
                            recoveryConsumer,
                            Duration.ofMillis(props.getClaimMinIdleTimeMs()),
                            RecordId.of(messageId)
                    );

            if (claimed == null || claimed.isEmpty()) {
                log.warn("Failed to claim message {} — not yet idle enough or already claimed", messageId);
                return false;
            }

            int processed = streamConsumer.processMessageBatch(claimed); // check kết quả

            if (processed == 0) {
                log.warn("Claimed message {} but insert returned 0, will retry next cycle", messageId);
                return false;
            }

            log.info("Recovered message {} from {} (idle: {}ms, attempts: {})",
                    messageId, pending.getConsumerName(),
                    pending.getElapsedTimeSinceLastDelivery().toMillis(),
                    pending.getTotalDeliveryCount());

            return true;

        } catch (Exception e) {
            log.error("Claim failed for message {}", pending.getIdAsString(), e);
            return false;
        }
    }
}