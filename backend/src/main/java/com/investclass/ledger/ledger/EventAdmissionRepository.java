package com.investclass.ledger.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** 迟到事件准入登记（独立于不可变事件本体）。 */
@Repository
public class EventAdmissionRepository {

    private final JdbcTemplate jdbc;

    public EventAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markLate(long eventId, String accountId, LocalDate admittedToDate) {
        jdbc.update("""
                INSERT INTO event_admission (event_id, account_id, admitted_to_date, late)
                VALUES (?, ?, ?, TRUE)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, accountId, Date.valueOf(admittedToDate));
    }

    public List<Long> lateEventIds(String accountId, LocalDate admittedToDate) {
        return jdbc.queryForList("""
                SELECT event_id FROM event_admission
                WHERE account_id = ? AND admitted_to_date = ?
                ORDER BY event_id
                """, Long.class, accountId, Date.valueOf(admittedToDate));
    }

    public boolean isLate(long eventId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_admission WHERE event_id = ? AND late = TRUE",
                Long.class, eventId);
        return v != null && v > 0;
    }
}
