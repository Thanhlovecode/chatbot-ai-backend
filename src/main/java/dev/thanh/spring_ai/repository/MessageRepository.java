package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<ChatMessage, UUID> {

  List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

  @Query("SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId ORDER BY m.createdAt DESC")
  List<ChatMessage> findRecentMessagesBySessionId(@Param("sessionId") UUID sessionId, Pageable pageable);

  @Query(value = """
      SELECT * FROM chat_messages m
      WHERE m.session_id = :sessionId
      AND (
        CAST(:lastCreatedAt AS TIMESTAMP) IS NULL
        OR m.created_at < :lastCreatedAt
        OR (m.created_at = :lastCreatedAt AND m.id < CAST(:lastId AS UUID))
      )
      ORDER BY m.created_at DESC, m.id DESC
      LIMIT :#{#pageable.pageSize}
      """, nativeQuery = true)
  List<ChatMessage> findMessagesCursorBased(@Param("sessionId") UUID sessionId,
      @Param("lastCreatedAt") LocalDateTime lastCreatedAt,
      @Param("lastId") UUID lastId,
      Pageable pageable);

  long countBySessionId(UUID sessionId);

  @Modifying
  @Transactional
  void deleteBySessionId(UUID sessionId);

  @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.role = 'USER' AND m.createdAt >= :todayStart")
  long countTodayUserMessages(@Param("todayStart") LocalDateTime todayStart);
}
