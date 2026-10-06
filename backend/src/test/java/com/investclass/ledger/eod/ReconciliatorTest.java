package com.investclass.ledger.eod;

import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliatorTest {

    private static final String ACC = "A1";
    private static final String STK = "AAA";

    private static Event trade(long id, String date, String settle, String side,
                               String qty, String price, String commission) {
        return new Event(id, EventType.TRADE, ACC, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        new BigDecimal(commission), "CNY"),
                "f", "T" + id, "i" + id, false, null);
    }

    private static Event split(long id, String ex, String ratio) {
        return new Event(id, EventType.STOCK_SPLIT, ACC, STK, LocalDate.parse(ex),
                null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)), "f", "S" + id,
                "i" + id, false, null);
    }

    private static List<Event> base() {
        return List.of(
                trade(1, "2026-08-01", "2026-08-02", "BUY", "100", "10.00", "0"),
                split(2, "2026-08-10", "3"),
                trade(3, "2026-08-11", "2026-08-12", "SELL", "150.5", "4.00", "0"));
    }

    @Test
    void cleanChainBalancesThreeSides() {
        AccountingProperties p = AccountingProperties.defaults();
        ReconciliationReport r = Reconciliator.reconcile(ACC, LocalDate.parse("2026-08-31"),
                3, base(), List.of(), p);
        assertThat(r.qtyBalanced()).isTrue();
        assertThat(r.cashBalanced()).isTrue();
        assertThat(r.costBalanced()).isTrue();
        assertThat(r.bookVsExternalBalanced()).isTrue();
        assertThat(r.allBalanced()).isTrue();
        assertThat(r.earliestMismatchEventId()).isNull();
    }

    @Test
    void externalMismatchBlocksAndPointsAtEarliestEvent() {
        AccountingProperties p = AccountingProperties.defaults();
        var stmt = List.of(new Reconciliator.ExternalStatement(STK,
                LocalDate.parse("2026-08-31"),
                new BigDecimal("200"), new BigDecimal("0"),       // 账面实际 149 + 0.5
                null, new BigDecimal("498.33")));
        ReconciliationReport r = Reconciliator.reconcile(ACC, LocalDate.parse("2026-08-31"),
                3, base(), stmt, p);
        assertThat(r.qtyBalanced()).isTrue();
        assertThat(r.cashBalanced()).isTrue();
        assertThat(r.bookVsExternalBalanced()).isFalse();
        assertThat(r.allBalanced()).isFalse();
        assertThat(r.earliestMismatchEventId()).isEqualTo(Long.valueOf(1L));
        assertThat(r.mismatchDetail()).contains("AAA");
    }

    private static Event dividend(long id, String ex, String record, String pay,
                                  String perShare) {
        return new Event(Long.valueOf(id), EventType.CASH_DIVIDEND, ACC, STK,
                LocalDate.parse(ex), null, LocalDate.parse(record), LocalDate.parse(pay),
                null, new EventPayload.CashDividend(new BigDecimal(perShare), "CNY"),
                "f", "D" + id, "i" + id, false, null);
    }

    @Test
    void cashConservationAcrossDividendAndFees() {
        List<Event> events = List.of(
                trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10.00", "5"),
                trade(2, "2026-09-24", "2026-09-25", "SELL", "40", "11.00", "5"),
                dividend(3, "2026-09-25", "2026-09-26", "2026-09-30", "0.5"));
        ReconciliationReport r = Reconciliator.reconcile(ACC, LocalDate.parse("2026-09-30"),
                3, events, List.of(), AccountingProperties.defaults());
        // 卖 40 在登记日前结算 -> 资格 60 股，分红 30；现金 = -1005+435+30 = -540
        assertThat(r.allBalanced()).isTrue();
        assertThat(r.projectedCash()).isEqualByComparingTo("-540.00");
    }
}
