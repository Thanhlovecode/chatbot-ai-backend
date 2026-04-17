package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.config.AbstractIntegrationTest;
import dev.thanh.spring_ai.entity.Session;
import dev.thanh.spring_ai.entity.User;
import dev.thanh.spring_ai.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionRepository Integration Test — runs against REAL PostgreSQL (Testcontainers).
 *
 * Focuses on the complex native query {@code findSessionsCursorBased} which uses
 * cursor-based pagination with composite key (updatedAt, id).
 * This query CANNOT be tested with unit tests — it requires real SQL execution.
 */
@DisplayName("SessionRepository — Integration Tests (Testcontainers PostgreSQL)")
class SessionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        userRepository.deleteAll();

        User user = User.builder()
                .email("test@gmail.com")
                .displayName("Test User")
                .googleId("google-123")
                .role(UserRole.USER)
                .active(true)
                .build();
        user = userRepository.save(user);
        userId = user.getId();
    }

    private Session createSession(String title, LocalDateTime updatedAt) {
        Session session = Session.builder()
                .userId(userId)
                .title(title)
                .updatedAt(updatedAt)
                .active(true)
                .build();
        return sessionRepository.save(session);
    }

    @Test
    @DisplayName("first page (null cursor) — should return sessions ordered by updatedAt DESC")
    void firstPage_ShouldReturnOrderedSessions() {
        Session s1 = createSession("Old", LocalDateTime.of(2024, 1, 1, 10, 0));
        Session s2 = createSession("Middle", LocalDateTime.of(2024, 6, 1, 10, 0));
        Session s3 = createSession("Recent", LocalDateTime.of(2024, 12, 1, 10, 0));

        List<Session> result = sessionRepository.findSessionsCursorBased(
                userId, null, null, PageRequest.of(0, 10));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getTitle()).isEqualTo("Recent");
        assertThat(result.get(1).getTitle()).isEqualTo("Middle");
        assertThat(result.get(2).getTitle()).isEqualTo("Old");
    }

    @Test
    @DisplayName("cursor pagination — second page should return only sessions before cursor")
    void cursorPagination_ShouldReturnNextPage() {
        Session s1 = createSession("Page2-A", LocalDateTime.of(2024, 1, 1, 10, 0));
        Session s2 = createSession("Page2-B", LocalDateTime.of(2024, 3, 1, 10, 0));
        Session s3 = createSession("Page1-A", LocalDateTime.of(2024, 6, 1, 10, 0));
        Session s4 = createSession("Page1-B", LocalDateTime.of(2024, 12, 1, 10, 0));

        // First page: get top 2
        List<Session> page1 = sessionRepository.findSessionsCursorBased(
                userId, null, null, PageRequest.of(0, 2));
        assertThat(page1).hasSize(2);

        // Second page: use cursor from last item of page1
        Session lastOfPage1 = page1.get(page1.size() - 1);
        List<Session> page2 = sessionRepository.findSessionsCursorBased(
                userId, lastOfPage1.getUpdatedAt(), lastOfPage1.getId(),
                PageRequest.of(0, 2));

        assertThat(page2).hasSize(2);
        // All page2 items should have updatedAt < cursor
        for (Session s : page2) {
            assertThat(s.getUpdatedAt()).isBeforeOrEqualTo(lastOfPage1.getUpdatedAt());
        }
    }

    @Test
    @DisplayName("inactive sessions — should be excluded from results")
    void inactiveSessions_ShouldBeExcluded() {
        createSession("Active", LocalDateTime.of(2024, 6, 1, 10, 0));

        Session inactive = Session.builder()
                .userId(userId)
                .title("Deleted")
                .updatedAt(LocalDateTime.of(2024, 12, 1, 10, 0))
                .active(false) // soft-deleted
                .build();
        sessionRepository.save(inactive);

        List<Session> result = sessionRepository.findSessionsCursorBased(
                userId, null, null, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Active");
    }

    @Test
    @DisplayName("different user — should not see other user's sessions")
    void differentUser_ShouldNotSeeOtherSessions() {
        createSession("My Session", LocalDateTime.of(2024, 6, 1, 10, 0));

        UUID otherUserId = UUID.randomUUID();
        List<Session> result = sessionRepository.findSessionsCursorBased(
                otherUserId, null, null, PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }
}
