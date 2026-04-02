package dev.thanh.spring_ai.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Batch update updated_at cho nhiều sessions cùng lúc.
 * Dùng bởi SessionSyncScheduler để đồng bộ dirty sessions từ Redis xuống DB.
 */
@Slf4j(topic = "BATCH-SESSION-REPO")
@Repository
@RequiredArgsConstructor
public class BatchSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String UPDATE_TIMESTAMP_SQL = """
            UPDATE chat_sessions SET updated_at = ? WHERE id = ?::uuid
            """;

    /**
     * Batch update updated_at cho map sessionId → timestamp.
     *
     * @return số rows affected
     */
    @Transactional
    public int batchUpdateTimestamps(Map<String, LocalDateTime> sessionTimestamps) {
        if (sessionTimestamps == null || sessionTimestamps.isEmpty()) {
            return 0;
        }

        List<Map.Entry<String, LocalDateTime>> entries = new ArrayList<>(sessionTimestamps.entrySet());
        long start = System.currentTimeMillis();

        int[] results = jdbcTemplate.batchUpdate(UPDATE_TIMESTAMP_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Map.Entry<String, LocalDateTime> entry = entries.get(i);
                ps.setTimestamp(1, Timestamp.valueOf(entry.getValue()));
                ps.setString(2, entry.getKey());
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });

        int updated = 0;
        for (int r : results) {
            if (r > 0) updated += r;
        }

        log.info("Batch updated {}/{} session timestamps in {}ms",
                updated, entries.size(), System.currentTimeMillis() - start);
        return updated;
    }
}
