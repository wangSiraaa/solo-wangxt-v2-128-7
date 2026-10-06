package com.investclass.ledger.eod;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 正式快照只读：快照仅加速，所有数值都可由事件账本重放复算。 */
@Repository
public class SnapshotRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PositionRow(LocalDate businessDate, String instrument, BigDecimal qty,
                              BigDecimal fractionalQty, BigDecimal openCost,
                              BigDecimal avgCost, BigDecimal realizedPnl,
                              long watermarkEventId) {
    }

    public List<PositionRow> positions(String accountId, LocalDate date) {
        return jdbc.query("""
                SELECT business_date, instrument, qty, fractional_qty, open_cost,
                       avg_cost, realized_pnl, watermark_event_id
                FROM snapshot_position
                WHERE account_id = :a AND business_date = :d
                ORDER BY instrument
                """,
                new MapSqlParameterSource("a", accountId)
                        .addValue("d", date, java.sql.Types.DATE),
                (rs, n) -> new PositionRow(rs.getObject(1, LocalDate.class),
                        rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getLong(8)));
    }

    public record CashRow(LocalDate businessDate, BigDecimal inflow, BigDecimal outflow,
                          BigDecimal balance, long watermarkEventId) {
    }

    public CashRow cash(String accountId, LocalDate date) {
        return jdbc.query("""
                SELECT business_date, inflow, outflow, balance, watermark_event_id
                FROM snapshot_cash WHERE account_id = :a AND business_date = :d
                """,
                new MapSqlParameterSource("a", accountId)
                        .addValue("d", date, java.sql.Types.DATE),
                rs -> rs.next() ? new CashRow(rs.getObject(1, LocalDate.class),
                        rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getLong(5)) : null);
    }
}
