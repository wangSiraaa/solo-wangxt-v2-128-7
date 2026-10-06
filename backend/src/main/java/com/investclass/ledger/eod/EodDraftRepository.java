package com.investclass.ledger.eod;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public class EodDraftRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EodDraftRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Draft(long id, String accountId, LocalDate businessDate, long watermark,
                        List<Long> lateEventIds, boolean qty, boolean cash, boolean cost,
                        Long earliestMismatch, String mismatchDetail, String status) {
    }

    public long createDraft(String accountId, LocalDate date, long watermark,
                            List<Long> lateEventIds) {
        return jdbc.getJdbcTemplate().queryForObject("""
                INSERT INTO eod_draft
                  (account_id, business_date, watermark_event_id, late_event_ids, status)
                VALUES (?, ?, ?, ?, 'DRAFT')
                ON CONFLICT (account_id, business_date) DO UPDATE
                  SET watermark_event_id = EXCLUDED.watermark_event_id,
                      late_event_ids = EXCLUDED.late_event_ids,
                      status = 'DRAFT', qty_balanced = FALSE, cash_balanced = FALSE,
                      cost_balanced = FALSE, earliest_mismatch_event_id = NULL,
                      mismatch_detail = NULL, published_at = NULL
                RETURNING id
                """, Long.class, accountId, date, watermark,
                lateEventIds.toArray(Long[]::new));
    }

    public void markResult(long id, boolean qty, boolean cash, boolean cost,
                           Long earliestMismatch, String detail, String status) {
        jdbc.update("""
                UPDATE eod_draft
                SET qty_balanced = :qty, cash_balanced = :cash, cost_balanced = :cost,
                    earliest_mismatch_event_id = :mid, mismatch_detail = :detail,
                    status = :status
                WHERE id = :id
                """,
                new MapSqlParameterSource("qty", qty).addValue("cash", cash)
                        .addValue("cost", cost)
                        .addValue("mid", earliestMismatch)
                        .addValue("detail", detail).addValue("status", status)
                        .addValue("id", id));
    }

    public void publish(long id) {
        jdbc.update("UPDATE eod_draft SET status='PUBLISHED', published_at=now() WHERE id=:id",
                new MapSqlParameterSource("id", id));
    }

    /** 记录已发布水位（迟到成交据此排除，不反向补造当日历史）。 */
    public void recordPublishedWatermark(String accountId, LocalDate date, long watermark) {
        jdbc.update("""
                INSERT INTO eod_published_watermark
                  (account_id, business_date, watermark_event_id)
                VALUES (:a, :d, :w)
                ON CONFLICT (account_id, business_date) DO NOTHING
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE)
                        .addValue("w", watermark));
    }

    public Long publishedWatermark(String accountId, LocalDate date) {
        var ids = jdbc.query("""
                        SELECT watermark_event_id FROM eod_published_watermark
                        WHERE account_id = :a AND business_date = :d
                        """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE),
                (rs, n) -> rs.getLong(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Draft find(String accountId, LocalDate date) {
        return jdbc.query("""
                        SELECT id, account_id, business_date, watermark_event_id,
                               late_event_ids, qty_balanced, cash_balanced, cost_balanced,
                               earliest_mismatch_event_id, mismatch_detail, status
                        FROM eod_draft
                        WHERE account_id = :a AND business_date = :d
                        """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    java.sql.Array arr = rs.getArray("late_event_ids");
                    @SuppressWarnings("unchecked")
                    List<Long> late = arr == null ? List.of()
                            : List.of((Long[]) arr.getArray());
                    return new Draft(rs.getLong(1), rs.getString(2),
                            rs.getObject(3, LocalDate.class), rs.getLong(4), late,
                            rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8),
                            rs.getObject(9, Long.class), rs.getString(10), rs.getString(11));
                });
    }

    public void upsertSnapshotPosition(String accountId, LocalDate date, String instrument,
                                       BigDecimal qty, BigDecimal fractionalQty,
                                       BigDecimal openCost, BigDecimal avgCost,
                                       BigDecimal realizedPnl, long watermark) {
        jdbc.update("""
                INSERT INTO snapshot_position
                  (account_id, business_date, instrument, qty, fractional_qty, open_cost,
                   avg_cost, realized_pnl, watermark_event_id)
                VALUES
                  (:a, :d, :i, :q, :fq, :oc, :ac, :pnl, :w)
                ON CONFLICT (account_id, business_date, instrument) DO UPDATE SET
                  qty = EXCLUDED.qty, fractional_qty = EXCLUDED.fractional_qty,
                  open_cost = EXCLUDED.open_cost, avg_cost = EXCLUDED.avg_cost,
                  realized_pnl = EXCLUDED.realized_pnl,
                  watermark_event_id = EXCLUDED.watermark_event_id
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE)
                        .addValue("i", instrument).addValue("q", qty)
                        .addValue("fq", fractionalQty).addValue("oc", openCost)
                        .addValue("ac", avgCost).addValue("pnl", realizedPnl)
                        .addValue("w", watermark));
    }

    public void upsertSnapshotLot(String accountId, LocalDate date,
                                  com.investclass.ledger.projection.Lot l) {
        jdbc.update("""
                INSERT INTO snapshot_lot
                  (account_id, business_date, lot_key, instrument, opening_event_id,
                   source_event_type, acquired_date, remaining_qty, unit_cost, total_cost,
                   fractional)
                VALUES
                  (:a, :d, :lk, :i, :oe, :st, :ad, :rq, :uc, :tc, :fr)
                ON CONFLICT (account_id, business_date, lot_key) DO UPDATE SET
                  remaining_qty = EXCLUDED.remaining_qty,
                  unit_cost = EXCLUDED.unit_cost,
                  total_cost = EXCLUDED.total_cost,
                  fractional = EXCLUDED.fractional
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE)
                        .addValue("lk", l.lotKey())
                        .addValue("i", l.instrument()).addValue("oe", l.openingEventId())
                        .addValue("st", l.sourceEventType()).addValue("ad", l.acquiredDate())
                        .addValue("rq", l.remainingQty()).addValue("uc", l.unitCost())
                        .addValue("tc", l.totalCost()).addValue("fr", l.fractional()));
    }

    public void upsertSnapshotCash(String accountId, LocalDate date, BigDecimal inflow,
                                   BigDecimal outflow, BigDecimal balance, long watermark) {
        jdbc.update("""
                INSERT INTO snapshot_cash
                  (account_id, business_date, inflow, outflow, balance, watermark_event_id)
                VALUES
                  (:a, :d, :in, :out, :b, :w)
                ON CONFLICT (account_id, business_date) DO UPDATE SET
                  inflow = EXCLUDED.inflow, outflow = EXCLUDED.outflow,
                  balance = EXCLUDED.balance, watermark_event_id = EXCLUDED.watermark_event_id
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE)
                        .addValue("in", inflow).addValue("out", outflow)
                        .addValue("b", balance).addValue("w", watermark));
    }
}
