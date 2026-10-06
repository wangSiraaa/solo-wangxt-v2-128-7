package com.investclass.ledger.projection.store;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/** 投影游标：每提交一个效应与检查点原子推进一格。 */
@Repository
public class ProjectionCursorRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectionCursorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Cursor(long lastEventId, LocalDate lastBusinessDate, String lastStage,
                         String lastEffectKey) {
    }

    public Cursor get(String accountId) {
        return jdbc.query("""
                        SELECT last_event_id, last_business_date, last_stage, last_effect_key
                        FROM projection_cursor WHERE account_id = :a
                        """, new MapSqlParameterSource("a", accountId),
                        rs -> rs.next() ? new Cursor(rs.getLong(1),
                                rs.getObject(2, LocalDate.class), rs.getString(3),
                                rs.getString(4)) : new Cursor(0, null, null, null));
    }

    /** 在效应事务内推进；与派生写入同生共死，崩溃后从最后一致格重放。 */
    public void advance(String accountId, long eventId, LocalDate businessDate,
                        String stage, String effectKey) {
        jdbc.update("""
                INSERT INTO projection_cursor
                  (account_id, last_event_id, last_business_date, last_stage,
                   last_effect_key, updated_at)
                VALUES (:a, :id, :d, :st, :ek, now())
                ON CONFLICT (account_id) DO UPDATE SET
                  last_event_id = EXCLUDED.last_event_id,
                  last_business_date = EXCLUDED.last_business_date,
                  last_stage = EXCLUDED.last_stage,
                  last_effect_key = EXCLUDED.last_effect_key,
                  updated_at = now()
                """,
                new MapSqlParameterSource("a", accountId).addValue("id", eventId)
                        .addValue("d", businessDate).addValue("st", stage)
                        .addValue("ek", effectKey));
    }

    public void reset(String accountId) {
        jdbc.update("DELETE FROM projection_cursor WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
    }
}
