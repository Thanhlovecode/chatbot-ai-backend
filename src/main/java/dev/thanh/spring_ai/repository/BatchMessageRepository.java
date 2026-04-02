package dev.thanh.spring_ai.repository;

import dev.thanh.spring_ai.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class BatchMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL = """
        INSERT INTO chat_messages (id, message_id, session_id, role, content, created_at, model)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (message_id) DO NOTHING
        """;

    @Transactional
    public int batchInsert(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;

        long start = System.currentTimeMillis();
        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, createBatchSetter(messages));
        int inserted = Arrays.stream(results).filter(r -> r > 0).sum();

        log.info("Batch insert: {}/{} messages in {}ms",
                inserted, messages.size(), System.currentTimeMillis() - start);

        return inserted;
    }

    public boolean singleInsert(ChatMessage msg) {
        int result = jdbcTemplate.update(INSERT_SQL,
                msg.getId(), msg.getMessageId(), msg.getSessionId(),
                msg.getRole().name(), msg.getContent(),
                Timestamp.valueOf(msg.getCreatedAt()), msg.getModel());
        return result > 0;
    }

    private BatchPreparedStatementSetter createBatchSetter(List<ChatMessage> messages) {
        return new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ChatMessage msg = messages.get(i);
                ps.setObject(1, msg.getId());
                ps.setString(2, msg.getMessageId());
                ps.setObject(3, msg.getSessionId());
                ps.setString(4, msg.getRole().name());
                ps.setString(5, msg.getContent());
                ps.setTimestamp(6, Timestamp.valueOf(msg.getCreatedAt()));
                ps.setString(7, msg.getModel());
            }

            @Override
            public int getBatchSize() {
                return messages.size();
            }
        };
    }
}