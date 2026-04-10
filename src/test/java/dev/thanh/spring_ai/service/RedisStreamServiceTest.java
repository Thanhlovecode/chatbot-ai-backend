package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RedisStreamProperties;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.enums.MessageRole;
import dev.thanh.spring_ai.repository.BatchMessageRepository;
import dev.thanh.spring_ai.utils.SafeRedisExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisStreamService.
 * Focuses on: pushToStream fallback chain (critical data safety path),
 * hasHistory null handling, and cacheHistory input guards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisStreamService — Unit Tests")
class RedisStreamServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RedisStreamProperties streamProperties;
    @Mock private MessageProcessorService messageProcessor;
    @Mock private DeadLetterQueueService dlqService;
    @Mock private BatchMessageRepository batchRepository;
    @Mock private SafeRedisExecutor safeRedis;
    @Mock private ChatMetricsService chatMetrics;
    @Mock private ListOperations<String, Object> listOps;

    @InjectMocks
    private RedisStreamService redisStreamService;

    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";
    private static final String HISTORY_KEY = "chat:history:" + SESSION_ID;

    private MessageDTO buildMessageDTO(MessageRole role, String content) {
        return MessageDTO.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(SESSION_ID)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ─── Helper: stub SafeRedis ───────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubFallback() {
        when(safeRedis.executeWithFallback(any(Supplier.class), any(Supplier.class), anyString()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @SuppressWarnings("unchecked")
    private void stubPassThrough() {
        when(safeRedis.executeWithFallback(any(Supplier.class), any(Supplier.class), anyString()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    }

    private void stubTryExecuteOrElseFallback() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(safeRedis).tryExecuteOrElse(any(Runnable.class), any(Runnable.class), anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // pushToStream — Critical fallback chain (Redis → DB → log)
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("pushToStream — fallback chain")
    class PushToStreamTests {

        @Test
        @DisplayName("when Redis fails → should fallback to direct DB insert")
        void whenRedisFails_ShouldFallbackToDirectDb() {
            stubTryExecuteOrElseFallback();
            when(batchRepository.singleInsert(any(ChatMessage.class))).thenReturn(true);

            redisStreamService.pushToStream(buildMessageDTO(MessageRole.USER, "Hello"));

            verify(batchRepository).singleInsert(any(ChatMessage.class));
        }

        @Test
        @DisplayName("when BOTH Redis AND DB fail → should NOT throw (CRITICAL log only)")
        void whenBothFail_ShouldNotThrow() {
            stubTryExecuteOrElseFallback();
            when(batchRepository.singleInsert(any(ChatMessage.class)))
                    .thenThrow(new RuntimeException("DB connection refused"));

            assertThatCode(() -> redisStreamService.pushToStream(
                    buildMessageDTO(MessageRole.USER, "Critical path")))
                    .doesNotThrowAnyException();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // hasHistory — null/zero handling
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("hasHistory — null/zero edge cases")
    class HasHistoryTests {

        @Test
        @DisplayName("when cache returns null → should return false (not NPE)")
        void whenCacheReturnsNull_ShouldReturnFalse() {
            stubPassThrough();
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(HISTORY_KEY)).thenReturn(null);

            assertThat(redisStreamService.hasHistory(SESSION_ID)).isFalse();
        }

        @Test
        @DisplayName("when Redis down → should return false (fail-closed)")
        void whenRedisFails_ShouldReturnFalse() {
            stubFallback();
            assertThat(redisStreamService.hasHistory(SESSION_ID)).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // cacheHistory — input guards
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("cacheHistory — input guards")
    class CacheHistoryTests {

        @Test
        @DisplayName("null messages → no Redis call")
        void whenNullMessages_ShouldSkip() {
            redisStreamService.cacheHistory(SESSION_ID, null);
            verifyNoInteractions(safeRedis);
        }

        @Test
        @DisplayName("empty messages → no Redis call")
        void whenEmptyMessages_ShouldSkip() {
            redisStreamService.cacheHistory(SESSION_ID, List.of());
            verifyNoInteractions(safeRedis);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getHistory — fallback behavior
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("getHistory — when Redis down → should return empty list (not null)")
    void getHistory_WhenRedisFails_ShouldReturnEmptyList() {
        stubFallback();
        assertThat(redisStreamService.getHistory(SESSION_ID)).isEmpty();
    }
}
