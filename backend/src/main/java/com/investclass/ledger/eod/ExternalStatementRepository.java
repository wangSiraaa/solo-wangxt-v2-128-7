package com.investclass.ledger.eod;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 外部对账单（托管/券商）：仅用于账实核对，绝不反向改写业务事件或快照历史。 */
@Repository
public class ExternalStatementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ExternalStatementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String accountId, LocalDate date, String instrument,
                       BigDecimal externalQty, BigDecimal externalFractionalQty,
                       BigDecimal externalCash, BigDecimal externalCost) {
        jdbc.update("""
                INSERT INTO external_statement
                  (account_id, business_date, instrument, external_qty,
                   external_fractional_qty, external_cash, external_cost)
                VALUES
                  (:a, :d, :i, :q, :fq, :cash, :cost)
                ON CONFLICT (account_id, business_date, instrument) DO UPDATE SET
                  external_qty = EXCLUDED.external_qty,
                  external_fractional_qty = EXCLUDED.external_fractional_qty,
                  external_cash = EXCLUDED.external_cash,
                  external_cost = EXCLUDED.external_cost
                """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE)
                        .addValue("i", instrument).addValue("q", externalQty)
                        .addValue("fq", externalFractionalQty)
                        .addValue("cash", externalCash).addValue("cost", externalCost));
    }

    public List<Reconciliator.ExternalStatement> find(String accountId, LocalDate date) {
        return jdbc.query("""
                        SELECT instrument, business_date, external_qty,
                               external_fractional_qty, external_cash, external_cost
                        FROM external_statement
                        WHERE account_id = :a AND business_date = :d
                        ORDER BY instrument
                        """,
                new MapSqlParameterSource("a", accountId).addValue("d", date, java.sql.Types.DATE),
                (rs, n) -> new Reconciliator.ExternalStatement(rs.getString(1),
                        rs.getObject(2, LocalDate.class), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
    }
}
