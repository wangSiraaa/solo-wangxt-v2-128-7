package com.investclass.ledger.importing;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class ImportBatchRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ImportBatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long register(String fileName, String sha256) {
        // 同一文件重复提交：sha256 唯一，返回已有批次并标记为 DUPLICATE（不影响事件幂等）
        var existing = jdbc.getJdbcTemplate().query(
                "SELECT id FROM import_batch WHERE sha256 = ?",
                (rs, n) -> rs.getLong(1), sha256);
        if (!existing.isEmpty()) {
            long id = existing.get(0);
            jdbc.getJdbcTemplate().update(
                    "UPDATE import_batch SET status='DUPLICATE' WHERE id=?", id);
            return -id;
        }
        return jdbc.getJdbcTemplate().queryForObject("""
                INSERT INTO import_batch (file_name, sha256, status)
                VALUES (?, ?, 'RECEIVED') RETURNING id
                """, Long.class, fileName, sha256);
    }

    public void running(long id, Long jobExecutionId, int totalRows) {
        jdbc.getJdbcTemplate().update("""
                UPDATE import_batch SET status='RUNNING', job_execution_id=?, total_rows=?
                WHERE id=?
                """, jobExecutionId, totalRows, id);
    }

    public void markStatus(long id, String status, String error) {
        jdbc.update("""
                        UPDATE import_batch
                        SET status = :s, error_message = :e, finished_at = :t
                        WHERE id = :id
                        """,
                new MapSqlParameterSource("s", status).addValue("e", error)
                        .addValue("t", Timestamp.from(Instant.now()), java.sql.Types.TIMESTAMP)
                        .addValue("id", id));
    }
}
