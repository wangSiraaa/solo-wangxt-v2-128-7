package com.investclass.ledger.projection;

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

/** 纯函数核算链验收：不依赖数据库。 */
class FoldEngineTest {

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

    private static Event dividend(long id, String ex, String record, String pay,
                                  String perShare) {
        return new Event(id, EventType.CASH_DIVIDEND, ACC, STK,
                LocalDate.parse(ex), null, LocalDate.parse(record),
                LocalDate.parse(pay), null,
                new EventPayload.CashDividend(new BigDecimal(perShare), "CNY"),
                "ca-feed", "D" + id, "idem-" + id, false, null);
    }

    private static Event split(long id, String ex, String ratio) {
        return new Event(id, EventType.STOCK_SPLIT, ACC, STK,
                LocalDate.parse(ex), null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)),
                "ca-feed", "S" + id, "idem-" + id, false, null);
    }

    private static Event rights(long id, String ex, String record, String pay,
                                String allot, String rightsPerShare,
                                String subPrice, String subscribed) {
        return new Event(id, EventType.RIGHTS_OFFER, ACC, STK,
                LocalDate.parse(ex), null, LocalDate.parse(record),
                LocalDate.parse(pay), LocalDate.parse(allot),
                new EventPayload.RightsOffer(new BigDecimal(rightsPerShare),
                        new BigDecimal(subPrice), new BigDecimal(subscribed), "CNY"),
                "ca-feed", "R" + id, "idem-" + id, false, null);
    }

    private static FoldState fold(List<Event> events) {
        AccountingProperties props = AccountingProperties.defaults();
        FoldState s = new FoldState(props);
        Map<Long, Event> byId = events.stream().collect(Collectors.toMap(Event::id, e -> e));
        FoldEngine.applyAll(s, EffectPlanner.plan(events).effects(), byId, ACC);
        return s;
    }

    private static FoldEngine.PositionView pos(FoldState s, String instrument) {
        return FoldEngine.positions(s).stream()
                .filter(p -> p.instrument().equals(instrument)).findFirst().orElseThrow();
    }

    @Test
    void crossRecordDateBuySellDividend() {
        // 案例：跨登记日买卖
        // 09-23 买 100（T+1 结算 09-24）；09-25 除权，09-26 登记，09-30 支付 0.5/股
        // 09-24 卖出 40（09-25 结算）-> 登记日在册 60 股，分红 30.00
        List<Event> events = List.of(
                trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10.00", "5"),
                trade(2, "2026-09-24", "2026-09-25", "SELL", "40", "11.00", "5"),
                dividend(3, "2026-09-25", "2026-09-26", "2026-09-30", "0.50"));
        FoldState s = fold(events);

        assertThat(pos(s, STK).qty()).isEqualByComparingTo("60");
        assertThat(s.entitlements.get("E3:ENTITLE").eligibleQty()).isEqualByComparingTo("60");
        assertThat(s.entitlements.get("E3:ENTITLE").grossAmount()).isEqualByComparingTo("30.00");
        assertThat(s.entitlements.get("E3:ENTITLE").status()).isEqualTo("PAID");
        // -1005（买入含佣）+ 435（卖出净收入）+ 30（分红）= -540?
        // 买入 -1005；卖出 40*11-5 = 435；分红 +30 -> -540，
        // 但资格股数为 60（登记日已卖 40，结算 09-25 <= 登记 09-26），现金 = -1005+435+30
        assertThat(FoldEngine.cashBalance(s)).isEqualByComparingTo("-540.00");
        // 批次成本：100 股总成本 1005，卖出 40 后剩余成本 = 1005*0.6 = 603.00
        assertThat(pos(s, STK).openCost()).isEqualByComparingTo("603.00");
    }

    @Test
    void sellSettlingAfterRecordDateKeepsDividend() {
        // 登记日后才结算的卖出不减少资格股数：09-28 卖 10，09-29 结算 -> 登记日仍 100
        List<Event> events = List.of(
                trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10.00", "0"),
                trade(2, "2026-09-28", "2026-09-29", "SELL", "10", "11.00", "0"),
                dividend(3, "2026-09-25", "2026-09-26", "2026-09-30", "0.50"));
        FoldState s = fold(events);
        assertThat(s.entitlements.get("E3:ENTITLE").eligibleQty()).isEqualByComparingTo("100");
    }

    @Test
    void splitThreeForOneThenPartialSellFractionalLotSeparated() {
        // 100 股 @10.00(成本 1000)，1 拆 3 -> 300 股；卖出 150.5 股
        // 剩 149 整股 + 0.5 零碎股（零碎股单列），成本严格守恒
        List<Event> events = List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "100", "10.00", "0"),
                split(2, "2026-08-10", "3"),
                trade(3, "2026-08-11", "2026-08-12", "SELL", "150.5", "4.00", "0"));
        FoldState s = fold(events);
        FoldEngine.PositionView p = pos(s, STK);
        assertThat(p.qty()).isEqualByComparingTo("149");
        assertThat(p.fractionalQty()).isEqualByComparingTo("0.5");
        // 总成本守恒：剩余 = 1000 * (149.5/300) = 498.33
        assertThat(p.openCost()).isEqualByComparingTo("498.33");
        // 卖出所得 = 150.5*4 = 602.00；已实现盈亏 = 602 - 501.67
        assertThat(s.realizedPnl).isEqualByComparingTo("100.33");
        assertThat(s.lots.values()).anyMatch(Lot::fractional);
    }

    @Test
    void splitAdjustsPendingTradeSettlementQty() {
        // 08-08 买 100，08-11 结算；08-10 一拆二 -> 结算 200 股，现金仍是原成交额
        List<Event> events = List.of(
                trade(1, "2026-08-08", "2026-08-11", "BUY", "100", "10.00", "0"),
                split(2, "2026-08-10", "2"));
        FoldState s = fold(events);
        assertThat(pos(s, STK).qty()).isEqualByComparingTo("200");
        assertThat(FoldEngine.cashBalance(s)).isEqualByComparingTo("-1000.00");
    }

    @Test
    void partialRightsSubscriptionCreatesOneLotAndCashOut() {
        // 100 股，10 配 2（资格 20 股 @3.00），只认购 7 股 -> 扣款 21，到账 7 股新批次
        List<Event> events = List.of(
                trade(1, "2026-07-01", "2026-07-02", "BUY", "100", "5.00", "0"),
                rights(2, "2026-07-08", "2026-07-10", "2026-07-12",
                        "2026-07-15", "0.2", "3.00", "7"));
        FoldState s = fold(events);
        Entitlement en = s.entitlements.get("E2:ENTITLE");
        assertThat(en.eligibleQty()).isEqualByComparingTo("20");
        assertThat(en.status()).isEqualTo("PARTIALLY_SUBSCRIBED");
        assertThat(en.subscribedQty()).isEqualByComparingTo("7");
        assertThat(pos(s, STK).qty()).isEqualByComparingTo("107");
        assertThat(s.lots.get("L2:ALLOT").totalCost()).isEqualByComparingTo("21.00");
        assertThat(FoldEngine.cashBalance(s)).isEqualByComparingTo("-521.00");
    }

    @Test
    void rightsNotSubscribedExpiresNoCashNoLot() {
        List<Event> events = List.of(
                trade(1, "2026-07-01", "2026-07-02", "BUY", "100", "5.00", "0"),
                rights(2, "2026-07-08", "2026-07-10", "2026-07-12",
                        "2026-07-15", "0.2", "3.00", "0"));
        FoldState s = fold(events);
        assertThat(s.entitlements.get("E2:ENTITLE").status()).isEqualTo("EXPIRED");
        assertThat(pos(s, STK).qty()).isEqualByComparingTo("100");
        assertThat(FoldEngine.cashBalance(s)).isEqualByComparingTo("-500.00");
    }

    @Test
    void replayIsIdempotentAfterCrashBetweenEntitleAndPay() {
        // 模拟崩溃在“权益已计算、现金尚未入账”：先只放到 ENTITLE，再从头重放全部
        List<Event> events = List.of(
                trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10.00", "0"),
                dividend(2, "2026-09-25", "2026-09-26", "2026-09-30", "0.50"));
        Map<Long, Event> byId = events.stream().collect(Collectors.toMap(Event::id, e -> e));
        List<Effect> all = EffectPlanner.plan(events).effects();

        // 找到登记日资格效应的位置，截断其后的支付效应
        int cut = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).kind() == Effect.Kind.DIVIDEND_PAY) {
                cut = i;
            }
        }
        FoldState partial = new FoldState(AccountingProperties.defaults());
        for (int i = 0; i < cut; i++) {
            FoldEngine.apply(partial, all.get(i), byId.get(all.get(i).eventId()), ACC);
        }
        BigDecimal cashBeforePay = FoldEngine.cashBalance(partial);
        assertThat(cashBeforePay).isEqualByComparingTo("-1000.00");

        // 从一致游标重放：已生效的全部跳过，只补支付，不重复增股/分红
        FoldState resumed = partial;
        for (int i = 0; i < cut; i++) {
            FoldEngine.apply(resumed, all.get(i), byId.get(all.get(i).eventId()), ACC);
        }
        for (int i = cut; i < all.size(); i++) {
            FoldEngine.apply(resumed, all.get(i), byId.get(all.get(i).eventId()), ACC);
        }
        assertThat(pos(resumed, STK).qty()).isEqualByComparingTo("100");
        assertThat(FoldEngine.cashBalance(resumed)).isEqualByComparingTo("-950.00");
        long dividendRows = resumed.cash.values().stream()
                .filter(c -> c.category().equals("DIVIDEND")).count();
        assertThat(dividendRows).isEqualTo(1);
        long buyLots = resumed.lots.values().stream()
                .filter(l -> l.sourceEventType().equals("TRADE")).count();
        assertThat(buyLots).isEqualTo(1);
    }

    @Test
    void oversellIsRejectedWithEarliestEvent() {
        List<Event> events = List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "10", "10.00", "0"),
                trade(2, "2026-08-03", "2026-08-04", "SELL", "11", "10.00", "0"));
        assertThatThrownBy(() -> fold(events))
                .hasMessageContaining("oversell");
    }
}
