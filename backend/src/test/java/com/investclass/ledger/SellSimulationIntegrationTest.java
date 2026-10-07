package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.FoldEngine;
import com.investclass.ledger.projection.ProjectionService;
import com.investclass.ledger.projection.SellSimulationService;
import com.investclass.ledger.projection.store.ProjectionCursorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卖出试算（只读 what-if）端到端验收（真实 PG）：
 *  - 拆股后卖出 150.5 股：预计批次消耗/零碎股/结转成本/预计损益与正式重放口径一致；
 *  - 连续试算两次，事件、批次、现金、检查点、游标账本数全部不变（不追加事件、
 *    不推进游标、不发布快照）；
 *  - 超出可卖数量给出缺口，试算数量封顶，不产生负批次；
 *  - 结算日晚于试算结算点的买入（未结算持仓）不可卖。
 */
class SellSimulationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private ProjectionService projection;
    @Autowired private SellSimulationService sim;
    @Autowired private ProjectionCursorRepository cursors;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Event trade(String acc, String stk, String date, String settle, String side,
                        String qty, String price, String sourceKey) {
        return new Event(null, EventType.TRADE, acc, stk, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "file", sourceKey, null, false, null);
    }

    private Event split(String acc, String stk, String ex, String ratio, String sourceKey) {
        return new Event(null, EventType.STOCK_SPLIT, acc, stk, LocalDate.parse(ex),
                null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)),
                "file", sourceKey, null, false, null);
    }

    private long insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("file|" + e.canonicalFingerprint());
        return events.insertIfAbsent(e, json, idem).event().id();
    }

    /** 账本各表行数快照：试算必须一笔不多、一笔不少。 */
    private Map<String, Long> ledgerCounts(String acc) {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : List.of("business_event", "projection_lot",
                "projection_lot_consumption", "projection_cash_entry",
                "projection_entitlement", "projection_checkpoint",
                "projection_cursor", "snapshot_position", "snapshot_lot",
                "snapshot_cash")) {
            Long n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE account_id = ?",
                    Long.class, acc);
            counts.put(table, n == null ? 0L : n);
        }
        return counts;
    }

    private static FoldEngine.PositionView posOf(List<FoldEngine.PositionView> positions,
                                                 String instrument) {
        return positions.stream().filter(p -> p.instrument().equals(instrument))
                .findFirst().orElse(null);
    }

    @Test
    void splitThenSell150_5CarryForwardVerifiableAndLedgerUntouched() throws Exception {
        String acc = "SIM1";
        String stk = "BBB";
        long buyId = insert(trade(acc, stk, "2026-08-01", "2026-08-02", "BUY",
                "100", "10.00", "SIM1-T1"));
        long splitId = insert(split(acc, stk, "2026-08-10", "3", "SIM1-S1"));
        projection.projectTo(acc, events.maxEventId(acc), true);

        Map<String, Long> countsBefore = ledgerCounts(acc);
        ProjectionCursorRepository.Cursor cursorBefore = cursors.get(acc);

        var req = new SellSimulationService.Request(stk, LocalDate.parse("2026-08-11"),
                LocalDate.parse("2026-08-12"), new BigDecimal("150.5"),
                new BigDecimal("4.00"));
        SellSimulationService.Result r = sim.simulate(acc, req);

        // 非正式入账；足额可卖
        assertThat(r.booked()).isFalse();
        assertThat(r.feasible()).isTrue();
        assertThat(r.shortfall()).isEqualByComparingTo("0");
        assertThat(r.availableQty()).isEqualByComparingTo("300");
        assertThat(r.settledQty()).isEqualByComparingTo("150.5");
        assertThat(r.simulatedQty()).isEqualByComparingTo("150.5");

        // 预计批次消耗：拆股后唯一批次 L{buy}:BUY 消耗 150.5 股，结转 501.67
        assertThat(r.takes()).hasSize(1);
        var take = r.takes().get(0);
        assertThat(take.lotKey()).isEqualTo("L" + buyId + ":BUY");
        assertThat(take.qty()).isEqualByComparingTo("150.5");
        assertThat(take.costReleased()).isEqualByComparingTo("501.67");
        assertThat(take.proceeds()).isEqualByComparingTo("602.00");
        assertThat(r.costReleased()).isEqualByComparingTo("501.67");
        assertThat(r.proceeds()).isEqualByComparingTo("602.00");
        assertThat(r.expectedPnl()).isEqualByComparingTo("100.33");

        // 剩余 149 整股 + 0.5 零碎股，成本 498.33（与场景 2 正式重放口径一致）
        var before = posOf(r.positionsBefore(), stk);
        assertThat(before.qty()).isEqualByComparingTo("300");
        assertThat(before.openCost()).isEqualByComparingTo("1000.00");
        var after = posOf(r.positionsAfter(), stk);
        assertThat(after.qty()).isEqualByComparingTo("149");
        assertThat(after.fractionalQty()).isEqualByComparingTo("0.5");
        assertThat(after.openCost()).isEqualByComparingTo("498.33");

        // 批次级核对：母批次剩 149 整股 @496.66，衍生零碎批 0.5 @1.67（尾差留在批次内）
        long simId = splitId + 1;
        var whole = r.lotsAfter().stream()
                .filter(l -> l.lotKey().equals("L" + buyId + ":BUY")).findFirst().orElseThrow();
        assertThat(whole.remainingQty()).isEqualByComparingTo("149");
        assertThat(whole.remainingCost()).isEqualByComparingTo("496.66");
        assertThat(whole.fractional()).isFalse();
        assertThat(whole.closed()).isFalse();
        var frac = r.lotsAfter().stream()
                .filter(l -> l.lotKey().equals("L" + simId + ":FRAC:L" + buyId + ":BUY"))
                .findFirst().orElseThrow();
        assertThat(frac.remainingQty()).isEqualByComparingTo("0.5");
        assertThat(frac.remainingCost()).isEqualByComparingTo("1.67");
        assertThat(frac.fractional()).isTrue();
        assertThat(frac.derivedFromLotKey()).isEqualTo("L" + buyId + ":BUY");
        // 成本守恒：149 整股 + 0.5 零碎 = 498.33 = 1000 - 501.67
        assertThat(whole.remainingCost().add(frac.remainingCost()))
                .isEqualByComparingTo("498.33");

        // 连续试算第二次：结果一致，且账本行数与游标完全不变
        SellSimulationService.Result again = sim.simulate(acc, req);
        assertThat(again.takes()).hasSize(1);
        assertThat(again.takes().get(0).lotKey()).isEqualTo(take.lotKey());
        assertThat(again.takes().get(0).costReleased())
                .isEqualByComparingTo(take.costReleased());
        assertThat(again.expectedPnl()).isEqualByComparingTo(r.expectedPnl());

        assertThat(ledgerCounts(acc)).isEqualTo(countsBefore);
        ProjectionCursorRepository.Cursor cursorAfter = cursors.get(acc);
        assertThat(cursorAfter.lastEventId()).isEqualTo(cursorBefore.lastEventId());
        assertThat(cursorAfter.lastEffectKey()).isEqualTo(cursorBefore.lastEffectKey());
        assertThat(cursorAfter.lastStage()).isEqualTo(cursorBefore.lastStage());
    }

    @Test
    void oversellGivesShortfallWithoutNegativeLots() throws Exception {
        String acc = "SIM2";
        String stk = "CCC";
        long buyId = insert(trade(acc, stk, "2026-08-01", "2026-08-02", "BUY",
                "100", "10.00", "SIM2-T1"));
        projection.projectTo(acc, events.maxEventId(acc), true);
        Map<String, Long> countsBefore = ledgerCounts(acc);

        SellSimulationService.Result r = sim.simulate(acc,
                new SellSimulationService.Request(stk, LocalDate.parse("2026-08-03"),
                        LocalDate.parse("2026-08-04"), new BigDecimal("250"),
                        new BigDecimal("11.00")));

        // 缺口 150：可卖 100，试算数量封顶在 100，不产生负批次
        assertThat(r.feasible()).isFalse();
        assertThat(r.availableQty()).isEqualByComparingTo("100");
        assertThat(r.simulatedQty()).isEqualByComparingTo("100");
        assertThat(r.shortfall()).isEqualByComparingTo("150");
        assertThat(r.takes()).hasSize(1);
        assertThat(r.takes().get(0).lotKey()).isEqualTo("L" + buyId + ":BUY");
        assertThat(r.takes().get(0).qty()).isEqualByComparingTo("100");
        assertThat(r.takes().get(0).costReleased()).isEqualByComparingTo("1000.00");
        // 收入按可卖部分等比例折算：100 * 11 = 1100
        assertThat(r.proceeds()).isEqualByComparingTo("1100.00");
        assertThat(r.expectedPnl()).isEqualByComparingTo("100.00");

        // 批次结清为零，绝不出现负数量/负成本
        assertThat(r.lotsAfter()).allMatch(l -> l.remainingQty().signum() >= 0
                && l.remainingCost().signum() >= 0);
        var closed = r.lotsAfter().stream()
                .filter(l -> l.lotKey().equals("L" + buyId + ":BUY")).findFirst().orElseThrow();
        assertThat(closed.closed()).isTrue();
        assertThat(closed.remainingQty()).isEqualByComparingTo("0");
        assertThat(posOf(r.positionsAfter(), stk)).isNull();

        assertThat(ledgerCounts(acc)).isEqualTo(countsBefore);
    }

    @Test
    void unsettledBuyIsNotSellableAtSimulationPoint() throws Exception {
        String acc = "SIM3";
        String stk = "DDD";
        long buy1 = insert(trade(acc, stk, "2026-08-01", "2026-08-02", "BUY",
                "100", "10.00", "SIM3-T1"));
        // 第二笔买入 09-01 才结算：晚于试算结算点，属于未结算持仓，不可卖
        insert(trade(acc, stk, "2026-08-10", "2026-09-01", "BUY",
                "50", "12.00", "SIM3-T2"));
        projection.projectTo(acc, events.maxEventId(acc), true);
        Map<String, Long> countsBefore = ledgerCounts(acc);

        SellSimulationService.Result r = sim.simulate(acc,
                new SellSimulationService.Request(stk, LocalDate.parse("2026-08-11"),
                        LocalDate.parse("2026-08-12"), new BigDecimal("120"),
                        new BigDecimal("15.00")));

        // 结算点可卖仅 100（未结算的 50 不计入），缺口 20
        assertThat(r.availableQty()).isEqualByComparingTo("100");
        assertThat(r.feasible()).isFalse();
        assertThat(r.shortfall()).isEqualByComparingTo("20");
        assertThat(r.simulatedQty()).isEqualByComparingTo("100");
        var before = posOf(r.positionsBefore(), stk);
        assertThat(before.qty()).isEqualByComparingTo("100");
        // 只消耗已结算的第一批
        assertThat(r.takes()).hasSize(1);
        assertThat(r.takes().get(0).lotKey()).isEqualTo("L" + buy1 + ":BUY");
        assertThat(r.takes().get(0).qty()).isEqualByComparingTo("100");
        assertThat(posOf(r.positionsAfter(), stk)).isNull();

        assertThat(ledgerCounts(acc)).isEqualTo(countsBefore);
    }
}
