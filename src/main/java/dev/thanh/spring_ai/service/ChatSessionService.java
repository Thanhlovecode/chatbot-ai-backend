package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.dto.response.ChatMessageResponse;
import dev.thanh.spring_ai.dto.response.CursorResponse;
import dev.thanh.spring_ai.dto.response.SessionResponse;
import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.entity.Session;
import dev.thanh.spring_ai.event.SessionCreatedEvent;
import dev.thanh.spring_ai.repository.MessageRepository;
import dev.thanh.spring_ai.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import dev.thanh.spring_ai.enums.SessionErrorCode;
import dev.thanh.spring_ai.exception.SessionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j(topic = "CHAT-SESSION-SERVICE")
@RequiredArgsConstructor
public class ChatSessionService {

    private final SessionRepository sessionRepository;
    private final SessionCacheService sessionCacheService;
    private final SessionActivityService sessionActivityService;
    private final UuidV7Generator uuidV7Generator;
    private final MessageRepository messageRepository;
    private final RedisStreamService redisStreamService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public Session createSessionWithTitle(String userId, String title) {
        Session session = Session.builder()
                .id(uuidV7Generator.generate())
                .userId(UUID.fromString(userId))
                .title(title)
                .active(true)
                .build();

        Session savedSession = sessionRepository.save(session);
        log.info("Created session: {} with title: {} for user: {}",
                savedSession.getId(), title, userId);

        return savedSession;
    }

    @Transactional
    public Session updateSessionTitle(String sessionId, String newTitle) {
        return sessionRepository.findById(UUID.fromString(sessionId))
                .map(session -> {
                    session.setTitle(newTitle);
                    log.info("Updated title for session {}: {}", sessionId, newTitle);
                    return sessionRepository.save(session);
                })
                .orElseGet(() -> {
                    log.warn("Session {} not found. Skipping title update.", sessionId);
                    return Session.builder()
                            .id(UUID.fromString(sessionId))
                            .title(newTitle)
                            .build();
                });
    }

    /**
     * Soft-delete a session: set active=false and hard-delete all messages.
     * Validates that the session belongs to the requesting user.
     */
    @Transactional
    public void deleteSession(String sessionId, String userId) {
        UUID sid = UUID.fromString(sessionId);
        UUID uid = UUID.fromString(userId);
        Session session = sessionRepository.findById(sid)
                .orElseThrow(() -> new SessionException(SessionErrorCode.SESSION_NOT_FOUND));

        if (!session.getUserId().equals(uid)) {
            throw new SessionException(SessionErrorCode.SESSION_ACCESS_DENIED);
        }

        // Hard-delete all messages in this session
        messageRepository.deleteBySessionId(sid);

        // Soft-delete the session
        session.setActive(false);
        sessionRepository.save(session);

        // Evict khỏi cache, xóa Redis history, và xóa khỏi activity ZSETs
        sessionCacheService.evict(sessionId);
        redisStreamService.clearHistory(sessionId);
        sessionActivityService.removeSession(userId, sessionId);

        log.info("Deleted session {} (soft-delete) and all its messages for user {}", sessionId, userId);
    }

    @Transactional
    public SessionResponse renameSession(String sessionId, String userId, String newTitle) {
        UUID sid = UUID.fromString(sessionId);
        UUID uid = UUID.fromString(userId);
        Session session = sessionRepository.findById(sid)
                .orElseThrow(() -> new SessionException(SessionErrorCode.SESSION_NOT_FOUND));

        if (!session.getUserId().equals(uid)) {
            throw new SessionException(SessionErrorCode.SESSION_ACCESS_DENIED);
        }

        session.setTitle(newTitle);
        Session newSession = sessionRepository.save(session);

        log.info("Renamed session {} to '{}' for user {}", sessionId, newTitle, userId);

        return SessionResponse.builder()
                .id(newSession.getId().toString())
                .title(newSession.getTitle())
                .createdAt(newSession.getCreatedAt())
                .updatedAt(newSession.getUpdatedAt())
                .build();
    }

    /**
     * KHÔNG dùng @Transactional ở đây — tránh giữ DB connection khi gọi Redis cache.
     * Chỉ khoanh vùng transaction đúng chỗ cần DB bằng TransactionTemplate.
     */
    public String getOrCreateSession(String sessionId, String userId) {
        // ── Case 1: Tạo session mới — chỉ INSERT cần transaction ──
        if (sessionId == null || sessionId.isEmpty()) {
            Session saved = transactionTemplate.execute(status -> {
                Session newSession = Session.builder()
                        .id(uuidV7Generator.generate())
                        .userId(UUID.fromString(userId))
                        .title("New Chat")
                        .active(true)
                        .build();
                return sessionRepository.save(newSession);
            }); // ← Connection trả lại NGAY tại đây!

            log.info("Created new session [{}] for user [{}]", saved.getId(), userId);
            // Event publish NGOÀI transaction — không giữ connection
            eventPublisher.publishEvent(new SessionCreatedEvent(this, saved));
            return saved.getId().toString();
        }

        // ── Case 2: Cache hit → trả về ngay, KHÔNG cần DB connection ──
        if (sessionCacheService.getIfCached(sessionId) != null) {
            return sessionId;
        }

        // ── Case 3: Cache miss → query DB để validate, rồi cache kết quả ──
        UUID sid = UUID.fromString(sessionId);
        UUID uid = UUID.fromString(userId);

        // findByIdAndActiveTrue() tự có @Transactional(readOnly=true) trong SimpleJpaRepository
        // → connection chỉ bị giữ đúng lúc query (~0.1ms), KHÔNG bao trùm Redis call bên dưới
        Session session = sessionRepository.findByIdAndActiveTrue(sid)
                .orElseThrow(() -> new SessionException(SessionErrorCode.SESSION_NOT_FOUND));

        if (!session.getUserId().equals(uid)) {
            log.warn("User {} attempted to access session {} owned by {}", userId, sessionId, session.getUserId());
            throw new SessionException(SessionErrorCode.SESSION_ACCESS_DENIED);
        }

        // Cache NGOÀI transaction — Redis call không giữ DB connection
        sessionCacheService.cacheSessionId(sessionId);
        return sessionId;
    }

    /**
     * KHÔNG dùng @Transactional — phần lớn case trả về từ Redis cache, KHÔNG cần DB connection.
     * Chỉ khi cache miss thì mới query DB.
     * findRecentMessagesBySessionId() tự có @Transactional(readOnly=true) từ Spring Data JPA.
     */
    public List<Message> prepareHistory(String sessionId, boolean isNewSession) {
        // ── Case 1: Session mới → không cần history, KHÔNG acquire connection ──
        if (isNewSession) {
            return Collections.emptyList();
        }

        // ── Case 2: Redis cache hit → trả về ngay, KHÔNG acquire connection ──
        if (redisStreamService.hasHistory(sessionId)) {
            List<MessageDTO> cachedHistory = redisStreamService.getHistory(sessionId);
            log.info("Using cached history from Redis: {} messages", cachedHistory.size());
            return convertToSpringMessages(cachedHistory);
        }

        // ── Case 3: Cache miss → query DB (connection chỉ giữ đúng lúc SELECT ~2ms) ──
        List<MessageDTO> dbHistory = messageRepository
                .findRecentMessagesBySessionId(UUID.fromString(sessionId), PageRequest.of(0, 20))
                .stream()
                .map(this::mapToMessageDTO)
                .sorted(Comparator.comparing(MessageDTO::getCreatedAt))
                .toList();

        // Cache kết quả vào Redis — Redis call NGOÀI scope DB
        if (!dbHistory.isEmpty()) {
            redisStreamService.cacheHistory(sessionId, dbHistory);
            log.info("Loaded and cached {} messages from DB", dbHistory.size());
        }

        return convertToSpringMessages(dbHistory);
    }

    private MessageDTO mapToMessageDTO(ChatMessage chatMessage) {
        return MessageDTO.builder()
                .id(chatMessage.getId().toString())
                .role(chatMessage.getRole())
                .sessionId(chatMessage.getSessionId().toString())
                .content(chatMessage.getContent())
                .createdAt(chatMessage.getCreatedAt())
                .build();
    }

    private List<Message> convertToSpringMessages(List<MessageDTO> messageDTOS) {
        return messageDTOS.stream()
                .map(MessageDTO::toSpringAiMessage)
                .toList();
    }

    /**
     * Lấy danh sách sessions theo cursor pagination, dùng Redis ZSET làm SSOT cho
     * ordering.
     * <p>
     * Flow:
     * <ol>
     * <li>Parse cursor (epoch millis String) → xác định vị trí trong ZSET</li>
     * <li>Nếu Redis ZSET trống (cold start) → fallback DB + warm up ZSET</li>
     * <li>Redis ZSET có data → reverseRangeByScore lấy session IDs theo thứ tự mới
     * nhất</li>
     * <li>Batch fetch metadata từ DB (WHERE IN) → giữ nguyên thứ tự Redis</li>
     * <li>Build nextCursor từ score cuối cùng (epoch millis)</li>
     * </ol>
     *
     * @param userId user ID
     * @param cursor epoch millis as String, null = first page
     * @param limit  page size, clamped to [1, 100]
     */
    @Transactional(readOnly = true)
    public CursorResponse<SessionResponse> getUserSessionsCursor(String userId, String cursor, int limit) {

        // Clamp limit vào [1, 100] — ngăn client gửi limit=999999 query toàn bộ DB
        limit = Math.clamp(limit, 1, 100);

        // Check Redis ZSET size — cold start fallback nếu trống
        Long zsetSize = sessionActivityService.getZSetSize(userId);
        if (zsetSize == null || zsetSize == 0) {
            return fallbackToDb(userId, cursor, limit, true);
        }

        // Parse cursor → upper bound score (exclusive)
        // Cursor format: "epochMillis::sessionId" — chỉ cần phần epochMillis cho Redis
        // ZSET
        double maxScore = Double.MAX_VALUE;
        if (StringUtils.hasText(cursor)) {
            try {
                String epochPart = cursor.contains("::") ? cursor.split("::")[0] : cursor;
                maxScore = Double.longBitsToDouble(
                        Double.doubleToLongBits((double) Long.parseLong(epochPart)) - 1);
            } catch (NumberFormatException e) {
                log.warn("Invalid cursor format: {}, falling back to first page", cursor);
            }
        }

        // Fetch thêm buffer để bù session bị xóa nhưng vẫn còn trong ZSET
        int fetchSize = limit + 10;
        Set<ZSetOperations.TypedTuple<Object>> tuples = sessionActivityService.reverseRangeByScoreWithScores(userId, 0,
                maxScore, 0, fetchSize);

        if (tuples == null || tuples.isEmpty()) {
            // Redis đã hết data trong vùng score này (ZSET chỉ cache ~5 trang đầu).
            // Fallback xuống DB để tiếp tục phân trang các trang sâu hơn.
            // isColdStart=false: KHÔNG warm up vì ZSET đã có top-50, add sessions cũ sẽ bị trim ngay.
            return fallbackToDb(userId, cursor, limit, false);
        }

        // Collect ordered session IDs từ Redis
        List<String> orderedIds = tuples.stream()
                .map(t -> (String) t.getValue())
                .filter(Objects::nonNull)
                .toList();

        // Batch fetch metadata từ DB (WHERE IN, chỉ active sessions)
        List<UUID> uuids = orderedIds.stream()
                .map(UUID::fromString)
                .toList();

        Map<String, Session> dbMap = sessionRepository.findActiveByIds(uuids)
                .stream()
                .collect(Collectors.toMap(s -> s.getId().toString(), s -> s));

        // Ghép Redis order + DB metadata, skip sessions đã bị xóa
        List<SessionResponse> data = new ArrayList<>();
        Double lastScore = null;

        for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
            if (data.size() >= limit)
                break;

            String sessionId = (String) tuple.getValue();
            Double score = tuple.getScore();
            if (sessionId == null || score == null)
                continue;

            Session session = dbMap.get(sessionId);
            if (session == null)
                continue; // session bị xóa trong DB, bỏ qua

            LocalDateTime effectiveUpdatedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(score.longValue()), ZoneId.systemDefault());

            data.add(SessionResponse.builder()
                    .id(sessionId)
                    .title(session.getTitle())
                    .createdAt(session.getCreatedAt())
                    .updatedAt(effectiveUpdatedAt)
                    .build());
            lastScore = score;
        }

        // hasMore: đã fill đủ limit VÀ Redis còn data phía sau
        boolean hasNext = data.size() == limit && tuples.size() > limit;
        // Cursor format: epochMillis::sessionId — đảm bảo nhất quán với DB fallback
        // path
        String nextCursor = null;
        if (hasNext && lastScore != null && !data.isEmpty()) {
            String lastSessionId = data.get(data.size() - 1).id();
            nextCursor = lastScore.longValue() + "::" + lastSessionId;
        }

        return CursorResponse.<SessionResponse>builder()
                .data(data)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    /**
     * Fallback khi Redis ZSET trống (cold start) hoặc deep pagination vượt quá cache.
     * Query DB theo updated_at DESC, trả kết quả.
     * Chỉ warm up Redis ZSET khi {@code isColdStart=true} — tránh warm up vô nghĩa
     * ở deep pages (sessions cũ sẽ bị trim ngay bởi MAX_ZSET_SIZE).
     *
     * @param isColdStart true nếu ZSET hoàn toàn trống, false nếu deep pagination
     */
    private CursorResponse<SessionResponse> fallbackToDb(String userId, String cursor, int limit,
            boolean isColdStart) {
        log.warn("Falling back to DB for user {} (coldStart={})", userId, isColdStart);

        // Parse cursor: format "epochMillis::sessionId" — cả 2 phần đều được dùng
        // epochMillis → lastUpdatedAt (cursor chính)
        // sessionId → lastId (tie-breaker khi 2 session cùng timestamp, tận dụng đủ
        // index)
        LocalDateTime lastUpdatedAt = null;
        UUID lastId = null;
        if (StringUtils.hasText(cursor)) {
            try {
                String[] parts = cursor.split("::");
                long epochMillis = Long.parseLong(parts[0]);
                lastUpdatedAt = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
                if (parts.length == 2) {
                    lastId = UUID.fromString(parts[1]);
                }
            } catch (Exception e) {
                log.warn("Invalid DB fallback cursor: {}, falling back to first page", cursor);
            }
        }

        Pageable pageable = PageRequest.of(0, limit + 1);
        List<Session> sessions = sessionRepository.findSessionsCursorBased(
                UUID.fromString(userId), lastUpdatedAt,
                lastId, pageable); // lastId làm tie-breaker: tận dụng đủ index (user_id, updated_at, id)

        boolean hasNext = sessions.size() > limit;
        if (hasNext) {
            sessions = sessions.subList(0, limit);
        }

        List<SessionResponse> data = sessions.stream()
                .map(session -> SessionResponse.builder()
                        .id(session.getId().toString())
                        .title(session.getTitle())
                        .createdAt(session.getCreatedAt())
                        .updatedAt(session.getUpdatedAt())
                        .build())
                .toList();

        // Warm up Redis ZSET chỉ khi cold start.
        // Deep pagination (isColdStart=false): ZSET đã có top-50 sessions mới nhất,
        // add sessions cũ hơn sẽ bị ZREMRANGEBYRANK trim ngay → wasted round-trip.
        if (isColdStart && !sessions.isEmpty()) {
            Map<String, Double> scores = new LinkedHashMap<>();
            for (Session s : sessions) {
                double score = (double) s.getUpdatedAt()
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                scores.put(s.getId().toString(), score);
            }
            sessionActivityService.warmUpFromDb(userId, scores);
        }

        // Cursor format: "epochMillis::sessionId" — thống nhất với Redis path
        // epochMillis cho Redis score bound, sessionId làm tie-breaker cho DB query
        String nextCursor = null;
        if (hasNext && !sessions.isEmpty()) {
            Session lastSession = sessions.get(sessions.size() - 1);
            long epochMillis = lastSession.getUpdatedAt()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            nextCursor = epochMillis + "::" + lastSession.getId();
        }

        return CursorResponse.<SessionResponse>builder()
                .data(data)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    @Transactional(readOnly = true)
    public CursorResponse<ChatMessageResponse> getMessagesCursor(String sessionId, String userId, String cursor,
            int limit) {

        // Clamp limit vào [1, 100] — ngăn client gửi limit=999999 query toàn bộ DB
        limit = Math.clamp(limit, 1, 100);

        UUID sid = UUID.fromString(sessionId);
        UUID uid = UUID.fromString(userId);

        // Security check: ensure session belongs to user
        Session session = sessionRepository.findById(sid)
                .orElseThrow(() -> new SessionException(SessionErrorCode.SESSION_NOT_FOUND));

        if (!session.getUserId().equals(uid)) {
            throw new SessionException(SessionErrorCode.SESSION_ACCESS_DENIED);
        }

        LocalDateTime lastCreatedAt = null;
        String lastId = null;

        if (StringUtils.hasText(cursor)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split("::");
                if (parts.length == 2) {
                    lastCreatedAt = LocalDateTime.parse(parts[0]);
                    lastId = parts[1];
                }
            } catch (Exception e) {
                log.warn("Invalid cursor format: {}", cursor);
            }
        }

        Pageable pageable = PageRequest.of(0, limit + 1);
        List<ChatMessage> messages = messageRepository.findMessagesCursorBased(
                sid,
                lastCreatedAt,
                lastId != null ? UUID.fromString(lastId) : null,
                pageable);

        boolean hasNext = messages.size() > limit;
        if (hasNext) {
            messages = messages.subList(0, limit);
        }

        List<ChatMessageResponse> data = messages.stream()
                .map(msg -> ChatMessageResponse.builder()
                        .id(msg.getId().toString())
                        .sessionId(msg.getSessionId().toString())
                        .role(msg.getRole().name())
                        .content(msg.getContent())
                        .createdAt(msg.getCreatedAt())
                        .model(msg.getModel())
                        .build())
                .toList();

        String nextCursor = null;
        if (hasNext && !messages.isEmpty()) {
            ChatMessage lastMsg = messages.get(messages.size() - 1);
            String rawCursor = lastMsg.getCreatedAt().toString() + "::" + lastMsg.getId().toString();
            nextCursor = Base64.getEncoder().encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));
        }

        return CursorResponse.<ChatMessageResponse>builder()
                .data(data)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
