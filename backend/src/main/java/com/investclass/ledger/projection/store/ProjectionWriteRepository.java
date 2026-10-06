package com.investclass.ledger.projection.store;

import com.investclass.ledger.projection.CashEntry;
import com.investclass.ledger.projection.Entitlement;
import com.investclass.ledger.projection.Lot;
import com.investclass.ledger.projection.LotConsumption;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 投影派生表写入。所有写入幂等（稳定唯一键 ON CONFLICT DO NOTHING），
 * 配合效应检查点保证崩溃重放不重复增加股份或现金。
 */
@Repository
public class ProjectionWriteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectionWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertLot(String accountId, Lot l) {
        jdbc.update("""
                INSERT INTO projection_lot
                  (account_id, lot_key, instrument, opening_event_id, source_event_type,
                   acquired_date, open_qty, remaining_qty, unit_cost, total_cost,
                   remaining_cost, fractional, closed,
                   derived_from_lot_key, adjusted_by_event_id)
                VALUES
                  (:a, :lk, :instr, :oe, :st, :ad, :oq, :rq, :uc, :tc,
                   :rc, :fr, :cl, :dfk, :adj)
                ON CONFLICT (account_id, lot_key) DO UPDATE SET
                  remaining_qty = EXCLUDED.remaining_qty,
                  remaining_cost = EXCLUDED.remaining_cost,
                  fractional = EXCLUDED.fractional,
                  closed = EXCLUDED.closed,
                  adjusted_by_event_id = EXCLUDED.adjusted_by_event_id
                """,
                new MapSqlParameterSource()
                        .addValue("a", accountId)
                        .addValue("lk", l.lotKey())
                        .addValue("instr", l.instrument())
                        .addValue("oe", l.openingEventId())
                        .addValue("st", l.sourceEventType())
                        .addValue("ad", l.acquiredDate())
                        .addValue("oq", l.openQty())
                        .addValue("rq", l.remainingQty())
                        .addValue("uc", l.unitCost())
                        .addValue("tc", l.totalCost())
                        .addValue("rc", l.remainingCost())
                        .addValue("fr", l.fractional())
                        .addValue("cl", l.closed())
                        .addValue("dfk", l.derivedFromLotKey())
                        .addValue("adj", l.adjustedByEventId()));
    }

    public void addConsumption(String accountId, LotConsumption c) {
        jdbc.update("""
                INSERT INTO projection_lot_consumption
                  (account_id, instrument, lot_key, selling_event_id, qty,
                   cost_released, proceeds, at_date)
                VALUES
                  (:a, :instr, :lk, :sell, :qty, :cost, :proc, :at)
                ON CONFLICT (account_id, selling_event_id, lot_key) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("a", accountId)
                        .addValue("instr", c.instrument())
                        .addValue("lk", c.lotKey())
                        .addValue("sell", c.sellingEventId())
                        .addValue("qty", c.qty())
                        .addValue("cost", c.costReleased())
                        .addValue("proc", c.proceeds())
                        .addValue("at", c.atDate()));
    }

    public void addCash(String accountId, CashEntry c) {
        jdbc.update("""
                INSERT INTO projection_cash_entry
                  (account_id, business_date, value_date, event_id, effect_key,
                   direction, amount, category, idem_key)
                VALUES
                  (:a, :bd, :vd, :eid, :ek, :dir, :amt, :cat, :idem)
                ON CONFLICT (idem_key) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("a", accountId)
                        .addValue("bd", c.businessDate())
                        .addValue("vd", c.valueDate())
                        .addValue("eid", c.eventId())
                        .addValue("ek", c.effectKey())
                        .addValue("dir", c.direction())
                        .addValue("amt", c.amount())
                        .addValue("cat", c.category())
                        .addValue("idem", c.idemKey()));
    }

    public void upsertEntitlement(String accountId, Entitlement en) {
        jdbc.update("""
                INSERT INTO projection_entitlement
                  (account_id, instrument, event_id, kind, record_date, payment_date,
                   eligible_qty, amount_per_share, gross_amount, status,
                   subscribed_qty, idem_key)
                VALUES
                  (:a, :instr, :eid, :kind, :rd, :pd, :eq, :aps, :ga, :st, :sq, :idem)
                ON CONFLICT (idem_key) DO UPDATE SET
                  status = EXCLUDED.status,
                  subscribed_qty = EXCLUDED.subscribed_qty
                """,
                new MapSqlParameterSource()
                        .addValue("a", accountId)
                        .addValue("instr", en.instrument())
                        .addValue("eid", en.eventId())
                        .addValue("kind", en.kind())
                        .addValue("rd", en.recordDate())
                        .addValue("pd", en.paymentDate())
                        .addValue("eq", en.eligibleQty())
                        .addValue("aps", en.amountPerShare())
                        .addValue("ga", en.grossAmount())
                        .addValue("st", en.status())
                        .addValue("sq", en.subscribedQty() == null ? BigDecimal.ZERO
                                : en.subscribedQty())
                        .addValue("idem", accountId + ":" + en.idemKey()));
    }

    public void checkpoint(String accountId, LocalDate businessDate, String stage,
                           long eventId, String effectKey, String sharesHash,
                           BigDecimal cashBalance, BigDecimal openCost,
                           BigDecimal realizedPnl) {
        jdbc.update("""
                INSERT INTO projection_checkpoint
                  (account_id, business_date, stage, event_id, effect_key,
                   shares_hash, cash_balance, open_cost, realized_pnl)
                VALUES
                  (:a, :d, :stage, :eid, :ek, :sh, :cash, :cost, :pnl)
                ON CONFLICT (account_id, effect_key) DO UPDATE SET
                  shares_hash = EXCLUDED.shares_hash,
                  cash_balance = EXCLUDED.cash_balance,
                  open_cost = EXCLUDED.open_cost,
                  realized_pnl = EXCLUDED.realized_pnl
                """,
                new MapSqlParameterSource()
                        .addValue("a", accountId)
                        .addValue("d", businessDate)
                        .addValue("stage", stage)
                        .addValue("eid", eventId)
                        .addValue("ek", effectKey)
                        .addValue("sh", sharesHash)
                        .addValue("cash", cashBalance)
                        .addValue("cost", openCost)
                        .addValue("pnl", realizedPnl));
    }

    public List<String> checkpointEffectKeys(String accountId) {
        return jdbc.queryForList("""
                SELECT effect_key FROM projection_checkpoint WHERE account_id = :a
                """, new MapSqlParameterSource("a", accountId), String.class);
    }

    public void clearAccountProjections(String accountId) {
        jdbc.update("DELETE FROM projection_lot WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
        jdbc.update("DELETE FROM projection_lot_consumption WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
        jdbc.update("DELETE FROM projection_cash_entry WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
        jdbc.update("DELETE FROM projection_entitlement WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
        jdbc.update("DELETE FROM projection_checkpoint WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
        jdbc.update("DELETE FROM projection_cursor WHERE account_id = :a",
                new MapSqlParameterSource("a", accountId));
    }
}
