package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.Session;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

  @Query(value = """
      SELECT * FROM chat_sessions s
      WHERE s.user_id = :userId
        AND s.active = true
        AND (
          CAST(:lastUpdatedAt AS TIMESTAMP) IS NULL
          OR s.updated_at < :lastUpdatedAt
          OR (s.updated_at = :lastUpdatedAt AND s.id < CAST(:lastId AS UUID))
        )
      ORDER BY s.updated_at DESC, s.id DESC
      LIMIT :#{#pageable.pageSize}
      """, nativeQuery = true)
  List<Session> findSessionsCursorBased(
      @Param("userId") UUID userId,
      @Param("lastUpdatedAt") LocalDateTime lastUpdatedAt,
      @Param("lastId") UUID lastId,
      Pageable pageable);

  Optional<Session> findByIdAndActiveTrue(UUID id);

  @Query("SELECT s FROM Session s WHERE s.id IN :ids AND s.active = true")
  List<Session> findActiveByIds(@Param("ids") List<UUID> ids);
}
