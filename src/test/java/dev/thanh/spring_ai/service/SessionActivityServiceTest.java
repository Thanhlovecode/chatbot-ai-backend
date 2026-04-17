package dev.thanh.spring_ai.service;

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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SessionActivityService.
 * Focuses on: early-return guards, popDirtySessions (non-CB-protected),
 * and fallback default values.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SessionActivityService — Unit Tests")
class SessionActivityServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SafeRedisExecutor safeRedis;

    @Mock
    private ZSetOperations<String, Object> zSetOps;

    @InjectMocks
    private SessionActivityService sessionActivityService;

    private static final String USER_ID = "user-123";
    private static final String SESSION_ID_1 = "session-001";

    // ─── Helper: stub SafeRedis ───────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubFallback() {
        when(safeRedis.executeWithFallback(any(Supplier.class), any(Supplier.class), anyString()))
                .thenAnswer(inv -> {
                    Supplier<?> fallback = inv.getArgument(1);
                    return fallback.get();
                });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getZSetSize — fallback default value
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("getZSetSize: Redis failure should return 0")
    void getZSetSize_WhenRedisFails_ShouldReturnZero() {
        when(safeRedis.executeOrReject(any(), any(), anyString()))
                .thenAnswer(inv -> {
                    Supplier<Long> fallback = inv.getArgument(1);
                    return fallback.get(); // simulate Redis failure -> execute fallback
                });
        long size = sessionActivityService.getZSetSize("any_key");
        assertThat(size).isEqualTo(0L);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // warmUpFromDb — early-return guards
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("warmUpFromDb — input guards")
    class WarmUpFromDbTests {

        @Test
        @DisplayName("null input → no Redis call")
        void whenNullInput_ShouldSkip() {
            sessionActivityService.warmUpFromDb(USER_ID, null);
            verifyNoInteractions(safeRedis);
        }

        @Test
        @DisplayName("empty map → no Redis call")
        void whenEmptyMap_ShouldSkip() {
            sessionActivityService.warmUpFromDb(USER_ID, Collections.emptyMap());
            verifyNoInteractions(safeRedis);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getSessionTimestamps — early-return guards
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("getSessionTimestamps — null sessionIds → empty map, no Redis call")
    void getSessionTimestamps_WhenNullIds_ShouldReturnEmpty() {
        assertThat(sessionActivityService.getSessionTimestamps(USER_ID, null)).isEmpty();
        verifyNoInteractions(safeRedis);
    }

    @Test
    @DisplayName("getSessionTimestamps — empty list → empty map, no Redis call")
    void getSessionTimestamps_WhenEmptyIds_ShouldReturnEmpty() {
        assertThat(sessionActivityService.getSessionTimestamps(USER_ID, List.of())).isEmpty();
        verifyNoInteractions(safeRedis);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // popDirtySessions — NOT CB-protected, has own try-catch
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("popDirtySessions — NOT CB-protected")
    class PopDirtySessionsTests {

        @Test
        @DisplayName("when Redis returns tuples → should convert to Map<sessionId, LocalDateTime>")
        void whenSuccess_ShouldConvert() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            Set<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
            tuples.add(ZSetOperations.TypedTuple.of(SESSION_ID_1, 1711350000000.0));
            when(zSetOps.popMin("system:dirty_sessions", 10)).thenReturn(tuples);

            Map<String, LocalDateTime> result = sessionActivityService.popDirtySessions(10);

            assertThat(result).hasSize(1).containsKey(SESSION_ID_1);
        }

        @Test
        @DisplayName("when Redis throws → should catch and return empty map (no crash)")
        void whenException_ShouldReturnEmptyMap() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.popMin("system:dirty_sessions", 10))
                    .thenThrow(new RuntimeException("Redis connection refused"));

            assertThat(sessionActivityService.popDirtySessions(10)).isEmpty();
        }

        @Test
        @DisplayName("when Redis returns null → should return empty map")
        void whenNull_ShouldReturnEmptyMap() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.popMin("system:dirty_sessions", 10)).thenReturn(null);

            assertThat(sessionActivityService.popDirtySessions(10)).isEmpty();
        }
    }
}
