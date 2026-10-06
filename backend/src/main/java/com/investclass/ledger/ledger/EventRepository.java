package com.investclass.ledger.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 事件账本仓储：仅追加。幂等凭证 = 规范化来源键哈希；
 * (source_system, source_key) 与 idempotency_key 双唯一约束，
 * 两个并发批次含同一成交/公司行动时数据库层面保证只登记一次。
 */
@Repository
public class EventRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record InsertResult(Event event, boolean duplicate) {
    }

    /**
     * 幂等插入。唯一冲突时返回已存在事件（duplicate=true），不更新任何列。
     */
    public InsertResult insertIfAbsent(Event e, String payloadJson, String idempotencyKey) {
        var p = new MapSqlParameterSource()
                .addValue("type", e.type().name())
                .addValue("accountId", e.accountId())
                .addValue("instrument", e.instrument())
                .addValue("businessDate", e.businessDate())
                .addValue("settlementDate", e.settlementDate())
                .addValue("recordDate", e.recordDate())
                .addValue("paymentDate", e.paymentDate())
                .addValue("allotmentDate", e.allotmentDate())
                .addValue("payload", payloadJson)
                .addValue("sourceSystem", e.sourceSystem())
                .addValue("sourceKey", e.sourceKey())
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("late", e.late());

        String sql = """
                INSERT INTO business_event
                  (event_type, account_id, instrument, business_date, settlement_date,
                   record_date, payment_date, allotment_date, payload,
                   source_system, source_key, idempotency_key, late_flag)
                VALUES
                  (:type, :accountId, :instrument, :businessDate, :settlementDate,
                   :recordDate, :paymentDate, :allotmentDate, CAST(:payload AS JSONB),
                   :sourceSystem, :sourceKey, :idempotencyKey, :late)
                ON CONFLICT (source_system, source_key) DO NOTHING
                RETURNING id, ingested_at
                """;
        List<IdAndTime> rows = jdbc.query(sql, p, (rs, n) ->
                new IdAndTime(rs.getLong("id"), rs.getTimestamp("ingested_at").toInstant()));
        if (!rows.isEmpty()) {
            Event saved = e.withId(rows.get(0).id(), idempotencyKey, rows.get(0).at());
            return new InsertResult(saved, false);
        }
        Event existing = findBySourceKey(e.sourceSystem(), e.sourceKey()).orElseThrow();
        return new InsertResult(existing, true);
    }

    public Optional<Event> findBySourceKey(String sourceSystem, String sourceKey) {
        return jdbc.query("""
                        SELECT * FROM business_event
                        WHERE source_system = :s AND source_key = :k
                        """,
                        new MapSqlParameterSource("s", sourceSystem).addValue("k", sourceKey),
                        rowMapper()).stream().findFirst();
    }

    public List<Event> findByAccountUpTo(String accountId, long watermarkEventId) {
        return jdbc.query("""
                        SELECT * FROM business_event
                        WHERE account_id = :a AND id <= :w
                        ORDER BY id
                        """,
                new MapSqlParameterSource("a", accountId).addValue("w", watermarkEventId),
                rowMapper());
    }

    public List<Event> findByAccountBetween(String accountId, long afterId, long toId) {
        return jdbc.query("""
                        SELECT * FROM business_event
                        WHERE account_id = :a AND id > :after AND id <= :to
                        ORDER BY id
                        """,
                new MapSqlParameterSource("a", accountId)
                        .addValue("after", afterId).addValue("to", toId),
                rowMapper());
    }

    /** 当前账户最大事件 id（可能含标记为迟到、尚未发布的事件）。 */
    public long maxEventId(String accountId) {
        Long v = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM business_event WHERE account_id = ?",
                Long.class, accountId);
        return v == null ? 0L : v;
    }

    public long maxEventIdAtOrBeforeBusinessDate(String accountId, LocalDate date) {
        Long v = jdbc.getJdbcTemplate().queryForObject("""
                        SELECT COALESCE(MAX(id), 0) FROM business_event
                        WHERE account_id = ? AND business_date <= ?
                        """, Long.class, accountId, date);
        return v == null ? 0L : v;
    }

    public List<Event> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM business_event WHERE id IN (:ids) ORDER BY id",
                new MapSqlParameterSource("ids", ids), rowMapper());
    }

    /** 冻结水位之后到达的事件（发布期间迟到）：
     *  业务日 <= upToDate 的属于当日迟到成交，归次日草稿；
     *  业务日更晚的天然属于未来草稿。这里返回全部，由调用方判定。 */
    public List<Event> arrivalsAfterWatermark(String accountId, long watermark) {
        return jdbc.query("""
                SELECT * FROM business_event
                WHERE account_id = :a AND id > :w
                ORDER BY id
                """,
                new MapSqlParameterSource("a", accountId).addValue("w", watermark),
                rowMapper());
    }

    public List<Event> lateArrivalsAfter(String accountId, long watermark,
                                         LocalDate upToDate) {
        return arrivalsAfterWatermark(accountId, watermark).stream()
                .filter(e -> !e.businessDate().isAfter(upToDate))
                .toList();
    }

    /** 某日草稿窗口：业务日 <= date 的全部事件（迟到事件是否纳入由 admission 决定）。 */
    public List<Event> findByAccountForDate(String accountId, LocalDate date) {
        return jdbc.query("""
                SELECT * FROM business_event
                WHERE account_id = :a AND business_date <= :d
                ORDER BY business_date, id
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE),
                rowMapper());
    }

    /** 下一草稿窗口：业务日 <= nextDate，且排除被标记为迟到、归属日 > nextDate 的事件。
     *  迟到当日事件在“下一草稿”中纳入（admitted_to_date = date+1）。 */
    public List<Event> findByAccountForDateExcludingLaterAdmissions(
            String accountId, LocalDate date) {
        return jdbc.query("""
                SELECT be.* FROM business_event be
                LEFT JOIN event_admission ea ON ea.event_id = be.id
                WHERE be.account_id = :a
                  AND be.business_date <= :d
                  AND (ea.event_id IS NULL OR ea.admitted_to_date <= :d)
                ORDER BY be.business_date, be.id
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE),
                rowMapper());
    }

    public List<Event> timeline(String accountId, LocalDate from, LocalDate to) {
        var p = new MapSqlParameterSource("a", accountId)
                .addValue("from", from, java.sql.Types.DATE).addValue("to", to, java.sql.Types.DATE);
        return jdbc.query("""
                SELECT * FROM business_event
                WHERE account_id = :a AND business_date BETWEEN :from AND :to
                ORDER BY business_date, id
                """, p, rowMapper());
    }

    private RowMapper<Event> rowMapper() {
        return (rs, n) -> {
            try {
                EventType type = EventType.valueOf(rs.getString("event_type"));
                EventPayload payload = mapper.readValue(rs.getString("payload"),
                        EventPayload.class);
                Timestamp ing = rs.getTimestamp("ingested_at");
                Instant ingestedAt = ing == null ? null : ing.toInstant();
                return new Event(
                        rs.getLong("id"), type,
                        rs.getString("account_id"), rs.getString("instrument"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("settlement_date", LocalDate.class),
                        rs.getObject("record_date", LocalDate.class),
                        rs.getObject("payment_date", LocalDate.class),
                        rs.getObject("allotment_date", LocalDate.class),
                        payload,
                        rs.getString("source_system"), rs.getString("source_key"),
                        rs.getString("idempotency_key"),
                        rs.getBoolean("late_flag"), ingestedAt);
            } catch (Exception ex) {
                throw new IllegalStateException("failed to map business_event", ex);
            }
        };
    }

    private record IdAndTime(long id, Instant at) {
    }
}
