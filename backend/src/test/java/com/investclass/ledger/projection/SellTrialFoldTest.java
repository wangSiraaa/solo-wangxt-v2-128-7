package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingException;
import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读卖出试算的纯函数验收：不依赖数据库。
 *  - 试算在副本上结转，真实状态批次/现金/损益不变；
 *  - 与真实 SELL_SETTLE 完全相同的 FIFO、零碎股拆批、尾差留批规则；
 *  - 超卖给缺口而不产生负批次。
 */
class SellTrialFoldTest {

    private static final String ACC = "A1";
    private static final String STK = "AAA";

    private static Event trade(long id, String date, String settle, String side,
                               String qty, String price, String commission) {
        return new Event(id, EventType.TRADE, ACC, STK,
                LocalDate.parse(date), LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        new BigDecimal(commission), "CNY"),
                "broker-file", "T" + id, "idem-" + id, false, null);
    }

    private static Event split(long id, String ex, String ratio) {
        return new Event(id, EventType.STOCK_SPLIT, ACC, STK,
                LocalDate.parse(ex), null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)),
                "ca-feed", "S" + id, "idem-" + id, false, null);
    }

    private static FoldState fold(List<Event> events) {
        AccountingProperties props = AccountingProperties.defaults();
        FoldState s = new FoldState(props);
        Map<Long, Event> byId = events.stream().collect(Collectors.toMap(Event::id, e -> e));
        FoldEngine.applyAll(s, EffectPlanner.plan(events).effects(), byId, ACC);
        return s;
    }

    @Test
    void trialAfterSplitConsumesFifoLotsAndSeparatesFractionalResidual() {
        // 拆股验收场景：100 股 1 拆 3（300 股，成本 1000），试算卖出 150.5 股 @4.00
        List<Event> events = List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "100", "10.00", "0"),
                split(2, "2026-08-10", "3"));
        FoldState base = fold(events);

        FoldState trial = base.copy();
        FoldEngine.TrialSellResult r = FoldEngine.simulateSell(trial, STK,
                LocalDate.parse("2026-08-12"), new BigDecimal("150.5"), new BigDecimal("602.00"));

        // 单一开放批次结转 150.5 股；释放成本 501.67，预计损益 100.33（与真实卖出场景一致）
        assertThat(r.takes()).hasSize(1);
        assertThat(r.takes().get(0).qty()).isEqualByComparingTo("150.5");
        assertThat(r.costReleasedTotal()).isEqualByComparingTo("501.67");
        assertThat(r.realizedPnlDelta()).isEqualByComparingTo("100.33");
        assertThat(r.shortfallQty()).isEqualByComparingTo("0");

        FoldEngine.PositionView after = FoldEngine.positions(trial).stream()
                .filter(p -> p.instrument().equals(STK)).findFirst().orElseThrow();
        assertThat(after.qty()).isEqualByComparingTo("149");
        assertThat(after.fractionalQty()).isEqualByComparingTo("0.5");
        assertThat(after.openCost()).isEqualByComparingTo("498.33");
        assertThat(trial.lots.values()).anyMatch(Lot::fractional);

        // 结转成本逐批可核对：剩余 + 释放 = 原剩余成本（尾差留在批次自身）
        assertThat(after.openCost().add(r.costReleasedTotal())).isEqualByComparingTo("1000.00");

        // 原状态（账本事实）完全不变：没有 consumption/现金，仍是 300 股 @1000
        FoldEngine.PositionView baseView = FoldEngine.positions(base).stream()
                .filter(p -> p.instrument().equals(STK)).findFirst().orElseThrow();
        assertThat(baseView.qty()).isEqualByComparingTo("300");
        assertThat(baseView.fractionalQty()).isEqualByComparingTo("0");
        assertThat(baseView.openCost()).isEqualByComparingTo("1000.00");
        assertThat(base.consumptions).isEmpty();
        assertThat(base.cash).hasSize(1); // 只有买入现金行
        assertThat(base.realizedPnl).isEqualByComparingTo("0");
    }

    @Test
    void trialAcrossTwoLotsAllocatesProceedsProportionallyWithTailInLastTake() {
        // 两批：100 股 @10（成本 1000）+ 100 股 @12（成本 1200），试算卖出 150 股 @14
        List<Event> events = List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "100", "10.00", "0"),
                trade(2, "2026-08-03", "2026-08-04", "BUY", "100", "12.00", "0"));
        FoldState base = fold(events);

        FoldState trial = base.copy();
        FoldEngine.TrialSellResult r = FoldEngine.simulateSell(trial, STK,
                LocalDate.parse("2026-08-05"), new BigDecimal("150"), new BigDecimal("2100.00"));

        assertThat(r.takes()).hasSize(2);
        assertThat(r.takes().get(0).lotKey()).endsWith(":BUY");
        assertThat(r.takes().get(0).qty()).isEqualByComparingTo("100");
        assertThat(r.takes().get(0).costReleased()).isEqualByComparingTo("1000.00");
        assertThat(r.takes().get(1).qty()).isEqualByComparingTo("50");
        assertThat(r.takes().get(1).costReleased()).isEqualByComparingTo("600.00");
        // 收入按成本比例：2100*1000/1600=1312.50；尾差 787.50 由最后一笔吸收
        assertThat(r.takes().get(0).proceeds()).isEqualByComparingTo("1312.50");
        assertThat(r.takes().get(1).proceeds()).isEqualByComparingTo("787.50");
        assertThat(r.costReleasedTotal()).isEqualByComparingTo("1600.00");
        assertThat(r.realizedPnlDelta()).isEqualByComparingTo("500.00");
    }

    @Test
    void trialOversellReturnsShortfallAndNoNegativeLot() {
        List<Event> events = List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "10", "10.00", "0"));
        FoldState base = fold(events);

        FoldState trial = base.copy();
        FoldEngine.TrialSellResult r = FoldEngine.simulateSell(trial, STK,
                LocalDate.parse("2026-08-05"), new BigDecimal("13"), new BigDecimal("130.00"));

        assertThat(r.coveredQty()).isEqualByComparingTo("10");
        assertThat(r.shortfallQty()).isEqualByComparingTo("3");
        // 所有批次剩余数量/成本非负，未人为制造负批次
        for (Lot l : trial.lots.values()) {
            assertThat(l.remainingQty().signum()).isGreaterThanOrEqualTo(0);
            assertThat(l.remainingCost().signum()).isGreaterThanOrEqualTo(0);
        }
        assertThat(trial.lots.values().stream().filter(Lot::isOpen).toList()).isEmpty();
    }

    @Test
    void realSellStillRejectsOversell() {
        // 真实入账路径的超卖规则不变：直接抛 AccountingException
        List<Event> events = List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "10", "10.00", "0"),
                trade(2, "2026-08-03", "2026-08-04", "SELL", "11", "10.00", "0"));
        assertThatThrownBy(() -> fold(events))
                .isInstanceOf(AccountingException.class)
                .hasMessageContaining("oversell");
    }
}
