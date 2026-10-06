package com.investclass.ledger.projection.store;

import com.investclass.ledger.projection.FoldEngine;
import com.investclass.ledger.projection.FoldState;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 投影只读视图：批次、现金、权益、检查点（按水位折叠结果已由投影服务落库）。 */
@Repository
public class ProjectionReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectionReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LotRow(long id, String lotKey, String instrument, long openingEventId,
                         String sourceEventType, LocalDate acquiredDate,
                         BigDecimal openQty, BigDecimal remainingQty,
                         BigDecimal unitCost, BigDecimal totalCost, BigDecimal remainingCost,
                         boolean fractional, boolean closed,
                         String derivedFromLotKey, Long adjustedByEventId) {
    }

    public List<LotRow> lots(String accountId) {
        return jdbc.query("""
                SELECT id, lot_key, instrument, opening_event_id, source_event_type,
                       acquired_date, open_qty, remaining_qty, unit_cost, total_cost,
                       remaining_cost, fractional, closed,
                       derived_from_lot_key, adjusted_by_event_id
                FROM projection_lot WHERE account_id = :a
                ORDER BY acquired_date, id
                """, new MapSqlParameterSource("a", accountId),
                (rs, n) -> new LotRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), rs.getObject(6, LocalDate.class),
                        rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBoolean(12),
                        rs.getBoolean(13), rs.getString(14),
                        rs.getObject(15, Long.class)));
    }

    public record CashRow(long eventId, LocalDate businessDate, LocalDate valueDate,
                          String effectKey, String direction, BigDecimal amount,
                          String category, String idemKey) {
    }

    public List<CashRow> cash(String accountId) {
        return jdbc.query("""
                SELECT event_id, business_date, value_date, effect_key, direction, amount,
                       category, idem_key
                FROM projection_cash_entry WHERE account_id = :a
                ORDER BY value_date, id
                """, new MapSqlParameterSource("a", accountId),
                (rs, n) -> new CashRow(rs.getLong(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getString(8)));
    }

    public BigDecimal cashBalance(String accountId) {
        BigDecimal v = jdbc.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount ELSE -amount END), 0)
                FROM projection_cash_entry WHERE account_id = ?
                """, BigDecimal.class, accountId);
        return v == null ? BigDecimal.ZERO : v;
    }

    public record EntitlementRow(long eventId, String instrument, String kind,
                                 LocalDate recordDate, LocalDate paymentDate,
                                 BigDecimal eligibleQty, BigDecimal amountPerShare,
                                 BigDecimal grossAmount, String status,
                                 BigDecimal subscribedQty) {
    }

    public List<EntitlementRow> entitlements(String accountId) {
        return jdbc.query("""
                SELECT event_id, instrument, kind, record_date, payment_date, eligible_qty,
                       amount_per_share, gross_amount, status, subscribed_qty
                FROM projection_entitlement WHERE account_id = :a
                ORDER BY record_date, event_id
                """, new MapSqlParameterSource("a", accountId),
                (rs, n) -> new EntitlementRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class),
                        rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getString(9), rs.getBigDecimal(10)));
    }

    public record CheckpointRow(long eventId, String effectKey, LocalDate businessDate,
                                String stage, String sharesHash, BigDecimal cashBalance,
                                BigDecimal openCost, BigDecimal realizedPnl) {
    }

    public List<CheckpointRow> checkpoints(String accountId) {
        return jdbc.query("""
                SELECT event_id, effect_key, business_date, stage, shares_hash, cash_balance,
                       open_cost, realized_pnl
                FROM projection_checkpoint WHERE account_id = :a
                ORDER BY id
                """, new MapSqlParameterSource("a", accountId),
                (rs, n) -> new CheckpointRow(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8)));
    }

    /** 从 FoldState 直接取持仓（发布前核对在同一次重放里调用，避免依赖可能被污染的投影表）。 */
    public static List<FoldEngine.PositionView> statePositions(FoldState state) {
        return FoldEngine.positions(state);
    }
}
