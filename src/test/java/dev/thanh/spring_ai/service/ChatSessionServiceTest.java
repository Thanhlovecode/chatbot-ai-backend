package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.entity.Session;
import dev.thanh.spring_ai.enums.MessageRole;
import dev.thanh.spring_ai.enums.SessionErrorCode;
import dev.thanh.spring_ai.event.SessionCreatedEvent;
import dev.thanh.spring_ai.exception.SessionException;
import dev.thanh.spring_ai.repository.MessageRepository;
import dev.thanh.spring_ai.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    // ─────────────────────────────────────────────────────────
    // getOrCreateSession
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getOrCreateSession")
    class GetOrCreateSessionTests {

        @Test
        @DisplayName("when sessionId is null — should create new session and publish event")
        void getOrCreateSession_WhenNewSession_ShouldCreateAndPublishEvent() {
            // Given
            UUID newId = UUID.randomUUID();
            when(uuidV7Generator.generate()).thenReturn(newId);
            Session saved = buildSession(newId, USER_UUID, true);
            when(sessionRepository.save(any(Session.class))).thenReturn(saved);

            // When
            String result = chatSessionService.getOrCreateSession(null, USER_ID);

            // Then
            assertThat(result).isEqualTo(newId.toString());
            verify(eventPublisher).publishEvent(any(SessionCreatedEvent.class));
        }

        @Test
        @DisplayName("when session exists in cache — should return immediately without DB call")
        void getOrCreateSession_WhenCacheHit_ShouldReturnWithoutDbCall() {
            // Given
            when(sessionCacheService.getIfCached(SESSION_ID)).thenReturn(SESSION_ID);

            // When
            String result = chatSessionService.getOrCreateSession(SESSION_ID, USER_ID);

            // Then
            assertThat(result).isEqualTo(SESSION_ID);
            verifyNoInteractions(sessionRepository);
        }

        @Test
        @DisplayName("when session not found in DB — should throw SESSION_NOT_FOUND")
        void getOrCreateSession_WhenSessionNotFound_ShouldThrowSessionException() {
            // Given
            when(sessionCacheService.getIfCached(SESSION_ID)).thenReturn(null);
            when(sessionRepository.findByIdAndActiveTrue(SESSION_UUID)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> chatSessionService.getOrCreateSession(SESSION_ID, USER_ID))
                    .isInstanceOf(SessionException.class)
                    .satisfies(ex -> assertThat(((SessionException) ex).getErrorCode())
                            .isEqualTo(SessionErrorCode.SESSION_NOT_FOUND));
        }

        @Test
        @DisplayName("when user does not own session — should throw SESSION_ACCESS_DENIED")
        void getOrCreateSession_WhenAccessDenied_ShouldThrow() {
            // Given
            UUID otherUser = UUID.randomUUID();
            Session session = buildSession(SESSION_UUID, otherUser, true);
            when(sessionCacheService.getIfCached(SESSION_ID)).thenReturn(null);
            when(sessionRepository.findByIdAndActiveTrue(SESSION_UUID)).thenReturn(Optional.of(session));

            // When / Then
            assertThatThrownBy(() -> chatSessionService.getOrCreateSession(SESSION_ID, USER_ID))
                    .isInstanceOf(SessionException.class)
                    .satisfies(ex -> assertThat(((SessionException) ex).getErrorCode())
                            .isEqualTo(SessionErrorCode.SESSION_ACCESS_DENIED));
        }
    }

    // ─────────────────────────────────────────────────────────
    // deleteSession
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSession")
    class DeleteSessionTests {

        @Test
        @DisplayName("when session not found — should throw SESSION_NOT_FOUND")
        void deleteSession_WhenSessionNotFound_ShouldThrow() {
            // Given
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> chatSessionService.deleteSession(SESSION_ID, USER_ID))
                    .isInstanceOf(SessionException.class)
                    .satisfies(ex -> assertThat(((SessionException) ex).getErrorCode())
                            .isEqualTo(SessionErrorCode.SESSION_NOT_FOUND));
        }

        @Test
        @DisplayName("when user does not own session — should throw SESSION_ACCESS_DENIED")
        void deleteSession_WhenAccessDenied_ShouldThrow() {
            // Given
            UUID otherUser = UUID.randomUUID();
            Session session = buildSession(SESSION_UUID, otherUser, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));

            // When / Then
            assertThatThrownBy(() -> chatSessionService.deleteSession(SESSION_ID, USER_ID))
                    .isInstanceOf(SessionException.class)
                    .satisfies(ex -> assertThat(((SessionException) ex).getErrorCode())
                            .isEqualTo(SessionErrorCode.SESSION_ACCESS_DENIED));
        }

        @Test
        @DisplayName("happy path — should soft-delete, evict cache, clear Redis history")
        void deleteSession_HappyPath_ShouldEvictCacheAndClearHistory() {
            // Given
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));
            when(sessionRepository.save(any())).thenReturn(session);

            // When
            chatSessionService.deleteSession(SESSION_ID, USER_ID);

            // Then
            verify(messageRepository).deleteBySessionId(SESSION_UUID);
            verify(sessionCacheService).evict(SESSION_ID);
            verify(redisStreamService).clearHistory(SESSION_ID);
            assertThat(session.isActive()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────
    // renameSession
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("renameSession")
    class RenameSessionTests {

        @Test
        @DisplayName("when session not found — should throw SESSION_NOT_FOUND")
        void renameSession_WhenNotFound_ShouldThrow() {
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatSessionService.renameSession(SESSION_ID, USER_ID, "New Title"))
                    .isInstanceOf(SessionException.class)
                    .satisfies(ex -> assertThat(((SessionException) ex).getErrorCode())
                            .isEqualTo(SessionErrorCode.SESSION_NOT_FOUND));
        }

        @Test
        @DisplayName("when access denied — should throw SESSION_ACCESS_DENIED")
        void renameSession_WhenAccessDenied_ShouldThrow() {
            UUID otherUser = UUID.randomUUID();
            Session session = buildSession(SESSION_UUID, otherUser, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> chatSessionService.renameSession(SESSION_ID, USER_ID, "New Title"))
                    .isInstanceOf(SessionException.class)
                    .satisfies(ex -> assertThat(((SessionException) ex).getErrorCode())
                            .isEqualTo(SessionErrorCode.SESSION_ACCESS_DENIED));
        }

        @Test
        @DisplayName("happy path — should return updated SessionResponse")
        void renameSession_HappyPath_ShouldReturnUpdatedSessionResponse() {
            // Given
            Session session = buildSession(SESSION_UUID, USER_UUID, true);
            when(sessionRepository.findById(SESSION_UUID)).thenReturn(Optional.of(session));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            var response = chatSessionService.renameSession(SESSION_ID, USER_ID, "Renamed Title");

            // Then
            assertThat(response.title()).isEqualTo("Renamed Title");
        }
    }

    // ─────────────────────────────────────────────────────────
    // prepareHistory
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("prepareHistory")
    class PrepareHistoryTests {

        @Test
        @DisplayName("when new session — should return empty list immediately")
        void prepareHistory_WhenNewSession_ShouldReturnEmptyList() {
            // When
            var result = chatSessionService.prepareHistory(SESSION_ID, true);

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(redisStreamService, messageRepository);
        }

        @Test
        @DisplayName("when Redis has cached history — should return from cache without DB call")
        void prepareHistory_WhenCacheHit_ShouldReturnFromCache() {
            // Given
            when(redisStreamService.hasHistory(SESSION_ID)).thenReturn(true);
            MessageDTO cachedMsg = MessageDTO.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionId(SESSION_ID)
                    .role(MessageRole.USER)
                    .content("Hello from cache")
                    .createdAt(LocalDateTime.now())
                    .build();
            when(redisStreamService.getHistory(SESSION_ID)).thenReturn(List.of(cachedMsg));

            // When
            var result = chatSessionService.prepareHistory(SESSION_ID, false);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isInstanceOf(UserMessage.class);
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("when cache miss — should load from DB and cache result")
        void prepareHistory_WhenCacheMiss_ShouldLoadFromDbAndCache() {
            // Given
            when(redisStreamService.hasHistory(SESSION_ID)).thenReturn(false);
            when(messageRepository.findRecentMessagesBySessionId(eq(SESSION_UUID), any(Pageable.class)))
                    .thenReturn(List.of()); // empty DB returns empty list

            // When
            var result = chatSessionService.prepareHistory(SESSION_ID, false);

            // Then
            assertThat(result).isEmpty();
            verify(messageRepository).findRecentMessagesBySessionId(any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────
    // getUserSessionsCursor
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserSessionsCursor")
    class GetUserSessionsCursorTests {

        @Test
        @DisplayName("when cursor given — should return first page")
        void getUserSessionsCursor_WhenNoCursor_ShouldReturnFirstPage() {
            // Given
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), eq(null), eq(null), any()))
                    .thenReturn(List.of());

            // When
            var response = chatSessionService.getUserSessionsCursor(USER_ID, null, 10);

            // Then
            assertThat(response.getData()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
        }

        @Test
        @DisplayName("when corrupted cursor — should silently ignore and return first page")
        void getUserSessionsCursor_WhenInvalidCursor_ShouldIgnoreAndReturnFirstPage() {
            // Given - invalid base64 cursor
            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of());

            // When - should not throw on bad cursor
            var response = chatSessionService.getUserSessionsCursor(USER_ID, "NOT_VALID_BASE64!!!", 10);

            // Then
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Bug #1 — when ZSET has page-1 data but cursor points beyond it, should fallback to DB")
        void getUserSessionsCursor_WhenRedisHasPage1ButCursorPointsDeeper_ShouldFallbackToDb() {
            // Given: ZSET không rỗng (có data của page 1) nhưng không có
            // session nào có score nhỏ hơn cursor của page 1
            long page1LastScore = System.currentTimeMillis() - 10_000;
            String cursor = String.valueOf(page1LastScore);

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(10L); // ZSET không rỗng
            // Redis trả về rỗng vì không có session cũ hơn cursor (mô phỏng cache miss)
            when(sessionActivityService.reverseRangeByScoreWithScores(
                    eq(USER_ID), eq(0.0), anyDouble(), eq(0L), anyLong()))
                    .thenReturn(Collections.emptySet());
            // DB fallback trả về 2 session cũ hơn
            Session s1 = buildSession(UUID.randomUUID(), USER_UUID, true);
            Session s2 = buildSession(UUID.randomUUID(), USER_UUID, true);
            when(sessionRepository.findSessionsCursorBased(any(), any(), eq(null), any()))
                    .thenReturn(List.of(s1, s2));

            // When
            var response = chatSessionService.getUserSessionsCursor(USER_ID, cursor, 10);

            // Then
            assertThat(response.getData()).hasSize(2); // phải tìm thấy data từ DB, không phải emptyList
            verify(sessionRepository).findSessionsCursorBased(any(), any(), eq(null), any());
        }

        @Test
        @DisplayName("Bug #2 — cursor from fallbackToDb must use epochMillis::sessionId format")
        void getUserSessionsCursor_WhenFallbackDbCreatesCursor_SubsequentCallShouldParse() {
            // Given: page 1 vía fallbackToDb, session có updatedAt cụ thể
            LocalDateTime updatedAt = LocalDateTime.of(2024, 3, 25, 10, 0, 0);
            long expectedEpochMillis = updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            when(sessionActivityService.getZSetSize(USER_ID)).thenReturn(0L);
            Session session = buildSession(UUID.randomUUID(), USER_UUID, true);
            session.setUpdatedAt(updatedAt);
            // trả về 2 session để trigger hasNext=true và sinh cursor
            when(sessionRepository.findSessionsCursorBased(any(), any(), any(), any()))
                    .thenReturn(List.of(session, buildSession(UUID.randomUUID(), USER_UUID, true)));

            // When: gọi page 1
            var page1 = chatSessionService.getUserSessionsCursor(USER_ID, null, 1);
            String nextCursor = page1.getNextCursor();

            // Then: cursor phải có format "epochMillis::sessionId"
            // epochMillis parse được bởi Long.parseLong → Redis path dùng được
            // sessionId → DB path dùng làm tie-breaker
            assertThat(nextCursor).isNotNull();
            String[] parts = nextCursor.split("::");
            assertThat(parts).hasSize(2);
            assertThat(Long.parseLong(parts[0])).isEqualTo(expectedEpochMillis); // epoch millis đúng
            assertThat(parts[1]).isEqualTo(session.getId().toString());           // sessionId đúng
        }
    }
}
