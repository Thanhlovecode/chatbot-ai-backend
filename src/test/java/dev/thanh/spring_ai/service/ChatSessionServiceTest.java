package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.dto.response.ChatMessageResponse;
import dev.thanh.spring_ai.dto.response.CursorResponse;
import dev.thanh.spring_ai.dto.response.SessionResponse;
import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.entity.Session;
import dev.thanh.spring_ai.enums.MessageRole;
import dev.thanh.spring_ai.enums.SessionErrorCode;
import dev.thanh.spring_ai.event.SessionCreatedEvent;
import dev.thanh.spring_ai.exception.SessionException;
import dev.thanh.spring_ai.repository.MessageRepository;
import dev.thanh.spring_ai.repository.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService — Unit Tests")
class ChatSessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SessionCacheService sessionCacheService;
    @Mock
    private SessionActivityService sessionActivityService;
    @Mock
    private UuidV7Generator uuidV7Generator;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private RedisStreamService redisStreamService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ChatSessionService chatSessionService;

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";
    private static final UUID USER_UUID = UUID.fromString(USER_ID);
    private static final UUID SESSION_UUID = UUID.fromString(SESSION_ID);

    private Session buildSession(UUID sessionId, UUID userId, boolean active) {
        return Session.builder()
                .id(sessionId)
                .userId(userId)
                .title("Test Session")
                .active(active)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Session buildSessionWithUpdatedAt(UUID sessionId, UUID userId, LocalDateTime updatedAt) {
        return Session.builder()
                .id(sessionId)
                .userId(userId)
                .title("Test Session")
                .active(true)
                .createdAt(updatedAt.minusDays(1))
                .updatedAt(updatedAt)
                .build();
    }

    /**
     * Helper: tạo ZSET tuple set cho mock Redis responses.
     */
    private LinkedHashSet<ZSetOperations.TypedTuple<Object>> buildTupleSet(List<String> ids, List<Double> scores) {
        LinkedHashSet<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            tuples.add(new DefaultTypedTuple<>(ids.get(i), scores.get(i)));
        }
        return tuples;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getOrCreateSession
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getOrCreateSession")
    class GetOrCreateSessionTests {

        @Test
        @DisplayName("when sessionId is null — should create new session and publish event")
        void getOrCreateSession_WhenNewSession_ShouldCreateAndPublishEvent() {
            UUID newId = UUID.randomUUID();
            when(uuidV7Generator.generate()).thenReturn(newId);
            Session saved = buildSession(newId, USER_UUID, true);
            when(sessionRepository.save(any(Session.class))).thenReturn(saved);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });

            String result = chatSessionService.getOrCreateSession(null, USER_ID);

            assertThat(result).isEqualTo(newId.toString());
            verify(eventPublisher).publishEvent(any(SessionCreatedEvent.class));
        }

        @Test
        @DisplayName("when session exists in cache — should return immediately without DB call")
        void getOrCreateSession_WhenCacheHit_ShouldReturnWithoutDbCall() {
            when(sessionCacheService.getIfCached(SESSION_ID)).thenReturn(SESSION_ID);

            String result = chatSessionService.getOrCreateSession(SESSION_ID, USER_ID);

            assertThat(result).isEqualTo(SESSION_ID);
            verifyNoInteractions(sessionRepository);
        }

        @Test
        @DisplayName("when cache miss but DB has session — should cache and return")
        void getOrCreateSession_WhenCacheMiss_ShouldCacheAndReturn() {
            when(sessionCacheService.getIfCached(SESSION_ID)).thenReturn(null);
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findByIdAndActiveTrue(SESSION_UUID)).thenReturn(Optional.of(session));

            String result = chatSessionService.getOrCreateSession(SESSION_ID, USER_ID);

            assertThat(result).isEqualTo(SESSION_ID);
            verify(sessionCacheService).cacheSessionId(SESSION_ID);
        }

    }

    // ═══════════════════════════════════════════════════════════════════════
    // deleteSession — happy path (verifies side-effect chain)
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("deleteSession — should soft-delete, hard-delete messages, evict cache, clear Redis, remove activity")
    void deleteSession_HappyPath_ShouldEvictCacheAndClearHistory() {
        Session session = buildSession(SESSION_UUID, USER_UUID, true);
        when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenReturn(session);

        chatSessionService.deleteSession(SESSION_ID, USER_ID);

        verify(messageRepository).deleteBySessionId(SESSION_UUID);
        verify(sessionCacheService).evict(SESSION_ID);
        verify(redisStreamService).clearHistory(SESSION_ID);
        verify(sessionActivityService).removeSession(USER_ID, SESSION_ID);
        assertThat(session.isActive()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // prepareHistory
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    @DisplayName("prepareHistory() tests")
    class PrepareHistoryTests {

        @Test
        @DisplayName("when new session — should return empty list immediately, NO Redis/DB calls")
        void prepareHistory_WhenNewSession_ShouldReturnEmptyList() {
            var result = chatSessionService.prepareHistory(SESSION_ID, true);

            assertThat(result).isEmpty();
            verifyNoInteractions(redisStreamService, messageRepository);
        }

        @Test
        @DisplayName("when Redis has cached history — should return from cache without DB call")
        void prepareHistory_WhenCacheHit_ShouldReturnFromCache() {
            when(redisStreamService.hasHistory(SESSION_ID)).thenReturn(true);
            List<MessageDTO> cached = List.of(
                    MessageDTO.builder().id(UUID.randomUUID().toString()).sessionId(SESSION_ID)
                            .role(MessageRole.USER).content("Hello").createdAt(LocalDateTime.now()).build(),
                    MessageDTO.builder().id(UUID.randomUUID().toString()).sessionId(SESSION_ID)
                            .role(MessageRole.ASSISTANT).content("Hi!").createdAt(LocalDateTime.now()).build()
            );
            when(redisStreamService.getHistory(SESSION_ID)).thenReturn(cached);

            var result = chatSessionService.prepareHistory(SESSION_ID, false);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).isInstanceOf(UserMessage.class);
            assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("when cache miss — should load from DB and cache result")
        void prepareHistory_WhenCacheMiss_ShouldLoadFromDbAndCache() {
            when(redisStreamService.hasHistory(SESSION_ID)).thenReturn(false);

            ChatMessage dbMsg = ChatMessage.builder()
                    .id(UUID.randomUUID()).messageId("msg-1").sessionId(SESSION_UUID)
                    .role(MessageRole.USER).content("From DB").createdAt(LocalDateTime.now()).build();
            when(messageRepository.findRecentMessagesBySessionId(eq(SESSION_UUID), any(Pageable.class)))
                    .thenReturn(List.of(dbMsg));

            var result = chatSessionService.prepareHistory(SESSION_ID, false);

            assertThat(result).hasSize(1);
            verify(redisStreamService).cacheHistory(eq(SESSION_ID), any());
        }

        @Test
        @DisplayName("when cache miss and DB is empty — should NOT cache empty result")
        void prepareHistory_WhenCacheMissAndDbEmpty_ShouldNotCache() {
            when(redisStreamService.hasHistory(SESSION_ID)).thenReturn(false);
            when(messageRepository.findRecentMessagesBySessionId(eq(SESSION_UUID), any(Pageable.class)))
                    .thenReturn(List.of());

            var result = chatSessionService.prepareHistory(SESSION_ID, false);

            assertThat(result).isEmpty();
            verify(redisStreamService, never()).cacheHistory(any(), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getUserSessionsCursor — Cursor Parsing Edge Cases
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getUserSessionsCursor — Cursor Edge Cases")
    class CursorParsingEdgeCases {

        @Test
        @DisplayName("null cursor — should treat as first page (maxScore = MAX_VALUE)")
        void nullCursor_ShouldReturnFirstPage() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), eq(null), eq(null), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            assertThat(response.getData()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
        }

        @Test
        @DisplayName("empty string cursor — should treat as first page")
        void emptyCursor_ShouldReturnFirstPage() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), eq(null), eq(null), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getUserSessionsCursor(USER_ID, "", 10);

            assertThat(response.getData()).isEmpty();
        }

        @Test
        @DisplayName("malformed cursor (random text) — should silently ignore and return first page")
        void malformedCursor_ShouldIgnoreAndReturnFirstPage() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getUserSessionsCursor(USER_ID, "NOT_A_NUMBER!!!", 10);

            assertThat(response).isNotNull();
            assertThat(response.getData()).isEmpty();
        }

        @Test
        @DisplayName("cursor with only epochMillis (no sessionId) — should parse epochMillis correctly")
        void cursorWithOnlyEpoch_ShouldParseCorrectly() {
            long epochMillis = System.currentTimeMillis() - 5000;
            String cursor = String.valueOf(epochMillis);

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            // Should not throw — epochMillis-only cursor is valid for Redis path,
            // and DB fallback parses it without sessionId tie-breaker
            var response = chatSessionService.getUserSessionsCursor(USER_ID, cursor, 10);
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("cursor with epochMillis::sessionId — should parse both parts")
        void cursorWithSessionId_ShouldParseBothParts() {
            long epochMillis = System.currentTimeMillis() - 5000;
            UUID lastSessionId = UUID.randomUUID();
            String cursor = epochMillis + "::" + lastSessionId;

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);

            // Verify DB fallback receives parsed lastId tie-breaker
            when(sessionRepository.findSessionsCursorBased(
                    eq(USER_UUID), any(LocalDateTime.class), eq(lastSessionId), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getUserSessionsCursor(USER_ID, cursor, 10);

            assertThat(response).isNotNull();
            verify(sessionRepository).findSessionsCursorBased(
                    eq(USER_UUID), any(LocalDateTime.class), eq(lastSessionId), any());
        }

        @Test
        @DisplayName("cursor near Long.MAX_VALUE — should not overflow during IEEE 754 bit subtraction")
        void cursorNearMaxValue_ShouldNotOverflow() {
            // Epoch millis tối đa thực tế (year 275760) — kiểm tra bit subtraction không bị lỗi
            String cursor = String.valueOf(Long.MAX_VALUE / 2);

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(5L);
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(Collections.emptySet());
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            // Should not throw ArithmeticException or produce NaN
            var response = chatSessionService.getUserSessionsCursor(USER_ID, cursor, 10);
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("cursor = '0' (epoch zero) — should parse as score = 0 and return first page")
        void cursorZero_ShouldWorkAsEarliestPossibleTime() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getUserSessionsCursor(USER_ID, "0", 10);
            assertThat(response).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getUserSessionsCursor — Limit Clamping
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getUserSessionsCursor — Limit Clamping [1, 100]")
    class LimitClampingTests {

        @ParameterizedTest(name = "limit={0} → clamped to 1")
        @ValueSource(ints = {-999, -1, 0})
        @DisplayName("when limit <= 0 — should clamp to 1")
        void limit_BelowMinimum_ShouldClampToOne(int limit) {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            Session session = buildSession(UUID.randomUUID(), USER_UUID, true);
            // limit=1 → PageRequest(0, 2) → nếu trả 2 records thì hasNext=true
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of(session, buildSession(UUID.randomUUID(), USER_UUID, true)));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, limit);

            // Clamped to 1 → data chỉ chứa 1 session, hasNext = true
            assertThat(response.getData()).hasSize(1);
            assertThat(response.isHasNext()).isTrue();
        }

        @ParameterizedTest(name = "limit={0} → clamped to 100")
        @ValueSource(ints = {101, 999, 999999, Integer.MAX_VALUE})
        @DisplayName("when limit > 100 — should clamp to 100")
        void limit_AboveMaximum_ShouldClampToHundred(int limit) {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            // Just ensure it doesn't request limit=999999 from DB
            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, limit);

            // Verify pageable has limit+1=101 (clamped to 100, +1 for hasNext check)
            ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(sessionRepository).findSessionsCursorBased(any(), any(), any(), pageCaptor.capture());
            assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(101);
        }

        @Test
        @DisplayName("limit within range [1, 100] — should use as-is")
        void limit_WithinRange_ShouldUseAsIs() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            chatSessionService.getUserSessionsCursor(USER_ID, null, 25);

            ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(sessionRepository).findSessionsCursorBased(any(), any(), any(), pageCaptor.capture());
            assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(26); // 25 + 1
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getUserSessionsCursor — Redis ZSET → DB Fallback → Warm-up Flow
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getUserSessionsCursor — ZSET ↔ DB Fallback Flow")
    class ZsetDbFallbackFlowTests {

        @Test
        @DisplayName("cold start: ZSET empty → fallback to DB → should warm up ZSET")
        void coldStart_ShouldFallbackToDbAndWarmUp() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            Session s1 = buildSession(UUID.randomUUID(), USER_UUID, true);
            Session s2 = buildSession(UUID.randomUUID(), USER_UUID, true);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of(s1, s2));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            // Should trigger warm-up since isColdStart=true
            verify(sessionActivityService).warmUpFromDb(eq(USER_ID), any(Map.class));
            assertThat(response.getData()).hasSize(2);
        }

        @Test
        @DisplayName("cold start: ZSET null size → fallback to DB → should warm up ZSET")
        void coldStart_NullZSetSize_ShouldFallbackAndWarmUp() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(null);
            Session s1 = buildSession(UUID.randomUUID(), USER_UUID, true);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of(s1));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            verify(sessionActivityService).warmUpFromDb(eq(USER_ID), any(Map.class));
            assertThat(response.getData()).hasSize(1);
        }

        @Test
        @DisplayName("deep pagination: ZSET has data but returns empty for cursor → fallback to DB without warm-up")
        void deepPagination_ShouldFallbackToDbWithoutWarmUp() {
            long page1LastScore = System.currentTimeMillis() - 10_000;
            String cursor = String.valueOf(page1LastScore);

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(10L);
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(Collections.emptySet());

            Session s1 = buildSession(UUID.randomUUID(), USER_UUID, true);
            when(sessionRepository.findSessionsCursorBased(any(), any(), eq(null), any()))
                    .thenReturn(List.of(s1));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, cursor, 10);

            // isColdStart=false → should NOT warm up
            verify(sessionActivityService, never()).warmUpFromDb(any(), any());
            assertThat(response.getData()).hasSize(1);
        }

        @Test
        @DisplayName("ZSET hit: sessions from Redis match DB → should return correct order and metadata")
        void zsetHit_ShouldReturnOrderedFromRedis() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            double score1 = System.currentTimeMillis() + 0.0;
            double score2 = score1 - 5000;

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(2L);
            LinkedHashSet<ZSetOperations.TypedTuple<Object>> tuples = buildTupleSet(
                    List.of(id1.toString(), id2.toString()),
                    List.of(score1, score2)
            );
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(tuples);

            Session session1 = buildSession(id1, USER_UUID, true);
            Session session2 = buildSession(id2, USER_UUID, true);
            when(sessionRepository.findActiveByIds(any()))
                    .thenReturn(List.of(session1, session2));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            assertThat(response.getData()).hasSize(2);
            // Order preserved from Redis (newest first)
            assertThat(response.getData().get(0).id()).isEqualTo(id1.toString());
            assertThat(response.getData().get(1).id()).isEqualTo(id2.toString());
        }

        @Test
        @DisplayName("ZSET hit but session deleted from DB → should skip deleted sessions")
        void zsetHit_DeletedSessionsInDb_ShouldBeSkipped() {
            UUID activeId = UUID.randomUUID();
            UUID deletedId = UUID.randomUUID();
            double now = System.currentTimeMillis() + 0.0;

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(2L);
            LinkedHashSet<ZSetOperations.TypedTuple<Object>> tuples = buildTupleSet(
                    List.of(deletedId.toString(), activeId.toString()),
                    List.of(now, now - 1000)
            );
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(tuples);

            // Only activeId exists in DB
            Session activeSession = buildSession(activeId, USER_UUID, true);
            when(sessionRepository.findActiveByIds(any()))
                    .thenReturn(List.of(activeSession));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            // Should only contain the active session, deleted one skipped
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).id()).isEqualTo(activeId.toString());
        }

        @Test
        @DisplayName("hasNext — when data fills limit AND Redis has more — should be true")
        void hasNext_WhenFull_ShouldBeTrue() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            double now = System.currentTimeMillis() + 0.0;

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(5L);
            // Return 2 tuples (more than limit=1)
            LinkedHashSet<ZSetOperations.TypedTuple<Object>> tuples = buildTupleSet(
                    List.of(id1.toString(), id2.toString()),
                    List.of(now, now - 1000)
            );
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(tuples);

            when(sessionRepository.findActiveByIds(any())).thenReturn(
                    List.of(buildSession(id1, USER_UUID, true), buildSession(id2, USER_UUID, true)));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 1);

            assertThat(response.isHasNext()).isTrue();
            assertThat(response.getNextCursor()).isNotNull();
            assertThat(response.getData()).hasSize(1);
        }

        @Test
        @DisplayName("hasNext — when data doesn't fill limit — should be false")
        void hasNext_WhenNotFull_ShouldBeFalse() {
            UUID id1 = UUID.randomUUID();
            double now = System.currentTimeMillis() + 0.0;

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(1L);
            LinkedHashSet<ZSetOperations.TypedTuple<Object>> tuples = buildTupleSet(
                    List.of(id1.toString()),
                    List.of(now)
            );
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(tuples);

            when(sessionRepository.findActiveByIds(any())).thenReturn(
                    List.of(buildSession(id1, USER_UUID, true)));

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            assertThat(response.isHasNext()).isFalse();
            assertThat(response.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("cursor format consistency — nextCursor should be 'epochMillis::sessionId'")
        void cursorFormat_ShouldBeConsistent() {
            LocalDateTime updatedAt = LocalDateTime.of(2024, 3, 25, 10, 0, 0);
            long expectedEpoch = updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            Session session = buildSessionWithUpdatedAt(UUID.randomUUID(), USER_UUID, updatedAt);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of(session, buildSession(UUID.randomUUID(), USER_UUID, true)));

            var page1 = chatSessionService.getUserSessionsCursor(USER_ID, null, 1);

            assertThat(page1.getNextCursor()).isNotNull();
            String[] parts = page1.getNextCursor().split("::");
            assertThat(parts).hasSize(2);
            assertThat(Long.parseLong(parts[0])).isEqualTo(expectedEpoch);
            assertThat(parts[1]).isEqualTo(session.getId().toString());
        }

        @Test
        @DisplayName("cold start with empty DB — should NOT warm up and return empty page")
        void coldStart_EmptyDb_ShouldNotWarmUp() {
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            verify(sessionActivityService, never()).warmUpFromDb(any(), any());
            assertThat(response.getData()).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getMessagesCursor
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("getMessagesCursor")
    class GetMessagesCursorTests {

        @Test
        @DisplayName("null cursor — should return first page of messages")
        void nullCursor_ShouldReturnFirstPage() {
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));

            ChatMessage msg = ChatMessage.builder()
                    .id(UUID.randomUUID()).messageId("msg-1").sessionId(SESSION_UUID)
                    .role(MessageRole.USER).content("Hello").createdAt(LocalDateTime.now()).build();
            when(messageRepository.findMessagesCursorBased(eq(SESSION_UUID), eq(null), eq(null), any()))
                    .thenReturn(List.of(msg));

            var response = chatSessionService.getMessagesCursor(SESSION_ID, USER_ID, null, 10);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.isHasNext()).isFalse();
            assertThat(response.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("valid Base64 cursor — should decode and pass to repository")
        void validBase64Cursor_ShouldDecodeAndPass() {
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));

            LocalDateTime createdAt = LocalDateTime.of(2024, 3, 25, 10, 30, 0);
            UUID lastMsgId = UUID.randomUUID();
            String rawCursor = createdAt.toString() + "::" + lastMsgId;
            String base64Cursor = Base64.getEncoder().encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));

            when(messageRepository.findMessagesCursorBased(
                    eq(SESSION_UUID), eq(createdAt), eq(lastMsgId), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getMessagesCursor(SESSION_ID, USER_ID, base64Cursor, 10);

            assertThat(response.getData()).isEmpty();
            verify(messageRepository).findMessagesCursorBased(
                    eq(SESSION_UUID), eq(createdAt), eq(lastMsgId), any());
        }

        @Test
        @DisplayName("malformed Base64 cursor — should silently ignore and return first page")
        void malformedCursor_ShouldIgnoreAndReturnFirstPage() {
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));
            when(messageRepository.findMessagesCursorBased(eq(SESSION_UUID), eq(null), eq(null), any()))
                    .thenReturn(List.of());

            // Should not throw on invalid cursor
            var response = chatSessionService.getMessagesCursor(SESSION_ID, USER_ID, "INVALID_BASE64!!!", 10);
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("hasNext — when DB returns more than limit — should be true with cursor")
        void hasNext_ShouldBeTrue_WhenMoreThanLimit() {
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));

            LocalDateTime now = LocalDateTime.now();
            ChatMessage msg1 = ChatMessage.builder()
                    .id(UUID.randomUUID()).messageId("m1").sessionId(SESSION_UUID)
                    .role(MessageRole.USER).content("A").createdAt(now).build();
            ChatMessage msg2 = ChatMessage.builder()
                    .id(UUID.randomUUID()).messageId("m2").sessionId(SESSION_UUID)
                    .role(MessageRole.ASSISTANT).content("B").createdAt(now.plusSeconds(1)).build();
            // Returning 2 records for limit=1 → hasNext=true
            when(messageRepository.findMessagesCursorBased(eq(SESSION_UUID), eq(null), eq(null), any()))
                    .thenReturn(List.of(msg1, msg2));

            var response = chatSessionService.getMessagesCursor(SESSION_ID, USER_ID, null, 1);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.isHasNext()).isTrue();
            assertThat(response.getNextCursor()).isNotNull();

            // Verify cursor is valid Base64 with createdAt::messageId
            String decoded = new String(Base64.getDecoder().decode(response.getNextCursor()), StandardCharsets.UTF_8);
            assertThat(decoded).contains("::");
        }

        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {-10, 0, 200})
        @DisplayName("limit clamping — should clamp to [1, 100]")
        void limitClamping_ShouldClampCorrectly(int limit) {
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));
            when(messageRepository.findMessagesCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            var response = chatSessionService.getMessagesCursor(SESSION_ID, USER_ID, null, limit);

            assertThat(response).isNotNull();
            // Verify clamped limit used in pageable
            ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(messageRepository).findMessagesCursorBased(any(), any(), any(), pageCaptor.capture());
            int clampedLimit = Math.clamp(limit, 1, 100);
            assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(clampedLimit + 1);
        }
    }
}
