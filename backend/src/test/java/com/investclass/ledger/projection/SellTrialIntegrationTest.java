package com.investclass.ledger.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读卖出试算端到端验收（真实嵌入式 PG）：
 *  1. 拆股后试算卖出 150.5 股：批次结转、整股/零碎股、成本与损益可核对；
 *  2. 连续试算两次，账本（事件/投影/游标/现金）数字不变；
 *  3. 输入超出可卖数量：返回缺口，不产生负批次、不写任何派生数据；
 *  4. 未在结算点交割的在途买入不可卖。
 *
 * 嵌入式 PG 在整个测试 JVM 内共享，且 business_event 带禁止 DELETE 的只追加触发器，
 * 所以每个测试方法使用独立账户（@BeforeEach 生成新 id），跨方法/重复运行互不串数据。
 */
class SellTrialIntegrationTest extends com.investclass.ledger.AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private SellTrialService trials;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    private static final String STK = "BBB";

    private String acc;

    @BeforeEach
    void freshAccount() {
        acc = "SC2T" + Long.toHexString(System.nanoTime());
    }

    private Event trade(String key, String date, String settle, String side,
                        String qty, String price) {
        return new Event(null, EventType.TRADE, acc, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "file", key, null, false, null);
    }

    private Event split(String key, String ex, String ratio) {
        return new Event(null, EventType.STOCK_SPLIT, acc, STK, LocalDate.parse(ex),
                null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)),
                "file", key, null, false, null);
    }

    private void insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("file|" + e.canonicalFingerprint());
        events.insertIfAbsent(e, json, idem);
    }

    private long count(String table) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE account_id = ?",
                Long.class, acc);
        return v == null ? 0 : v;
    }

    @Test
    void trialAfterSplitThenRepeatedTrialLeavesLedgerUntouched() throws Exception {
        // 教学场景2：100 股 @10，08-10 一拆三；试算 08-11 卖出 150.5 股（08-12 结算）@4.00
        insert(trade(acc + "-SPLIT-T1", "2026-08-01", "2026-08-02", "BUY", "100", "10.00"));
        insert(split(acc + "-SPLIT-S1", "2026-08-10", "3"));

        LocalDate bd = LocalDate.parse("2026-08-11");
        LocalDate sd = LocalDate.parse("2026-08-12");

        SellTrialService.Report r1 = trials.trial(acc, STK, bd, sd,
                new BigDecimal("150.5"), new BigDecimal("4.00"), BigDecimal.ZERO);

        assertThat(r1.booked()).isFalse();
        assertThat(r1.entryKind()).isEqualTo(SellTrialService.ENTRY_KIND);
        assertThat(r1.sellableQty()).isEqualByComparingTo("300");
        assertThat(r1.coveredQty()).isEqualByComparingTo("150.5");
        assertThat(r1.shortfallQty()).isEqualByComparingTo("0");
        assertThat(r1.oversold()).isFalse();

        // 批次结转可核对：单批释放 150.5 股、成本 501.67
        assertThat(r1.consumptions()).hasSize(1);
        SellTrialService.TakeView take = r1.consumptions().get(0);
        assertThat(take.qty()).isEqualByComparingTo("150.5");
        assertThat(take.costReleased()).isEqualByComparingTo("501.67");
        assertThat(take.proceeds()).isEqualByComparingTo("602.00");

        // 剩余 149 整股 + 0.5 零碎股（零碎批单列），成本 498.33
        assertThat(r1.after().wholeQty()).isEqualByComparingTo("149");
        assertThat(r1.after().fractionalQty()).isEqualByComparingTo("0.5");
        assertThat(r1.after().remainingCost()).isEqualByComparingTo("498.33");
        assertThat(r1.costReleased()).isEqualByComparingTo("501.67");
        assertThat(r1.netProceeds()).isEqualByComparingTo("602.00");
        assertThat(r1.realizedPnl()).isEqualByComparingTo("100.33");
        assertThat(r1.lotChanges()).anyMatch(SellTrialService.LotView::fractional);

        // 连续第二次试算：与第一次完全一致
        SellTrialService.Report r2 = trials.trial(acc, STK, bd, sd,
                new BigDecimal("150.5"), new BigDecimal("4.00"), BigDecimal.ZERO);
        assertThat(r2.after().wholeQty()).isEqualTo(r1.after().wholeQty());
        assertThat(r2.after().fractionalQty()).isEqualTo(r1.after().fractionalQty());
        assertThat(r2.after().remainingCost()).isEqualTo(r1.after().remainingCost());
        assertThat(r2.realizedPnl()).isEqualTo(r1.realizedPnl());

        // 账本数不变：事件仅 2 条，没有任何投影派生行、检查点或游标
        assertThat(count("business_event")).isEqualTo(2);
        assertThat(count("projection_lot")).isZero();
        assertThat(count("projection_lot_consumption")).isZero();
        assertThat(count("projection_cash_entry")).isZero();
        assertThat(count("projection_checkpoint")).isZero();
        Long cursorRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM projection_cursor WHERE account_id = ?", Long.class, acc);
        assertThat(cursorRows).isZero();
    }

    @Test
    void oversellReportsShortfallWithoutNegativeLots() throws Exception {
        insert(trade(acc + "-OVER-T1", "2026-09-01", "2026-09-02", "BUY", "10", "20.00"));

        SellTrialService.Report r = trials.trial(acc, STK,
                LocalDate.parse("2026-09-05"), LocalDate.parse("2026-09-06"),
                new BigDecimal("12.5"), new BigDecimal("21.00"), BigDecimal.ZERO);

        assertThat(r.oversold()).isTrue();
        assertThat(r.sellableQty()).isEqualByComparingTo("10");
        assertThat(r.coveredQty()).isEqualByComparingTo("10");
        assertThat(r.shortfallQty()).isEqualByComparingTo("2.5");
        // 缺口部分不产生收入：净收入只对应 10 股
        assertThat(r.grossProceeds()).isEqualByComparingTo("210.00");
        assertThat(r.after().wholeQty()).isEqualByComparingTo("0");
        assertThat(r.after().fractionalQty()).isEqualByComparingTo("0");
        for (SellTrialService.LotView l : r.lotChanges()) {
            assertThat(l.remainingQtyAfter().signum()).isGreaterThanOrEqualTo(0);
            assertThat(l.remainingCostAfter().signum()).isGreaterThanOrEqualTo(0);
        }
        // 没有任何派生数据落库
        assertThat(count("projection_lot")).isZero();
        assertThat(count("projection_lot_consumption")).isZero();
        assertThat(count("business_event")).isEqualTo(1);
    }

    @Test
    void unsettledBuyIsNotSellableAtSettlementPoint() throws Exception {
        // 07-01 买入 10（07-02 结算）；07-03 又买入 5（07-08 才结算）
        insert(trade(acc + "-UNSET-T1", "2026-07-01", "2026-07-02", "BUY", "10", "30.00"));
        insert(trade(acc + "-UNSET-T2", "2026-07-03", "2026-07-08", "BUY", "5", "30.00"));

        SellTrialService.Report r = trials.trial(acc, STK,
                LocalDate.parse("2026-07-04"), LocalDate.parse("2026-07-05"),
                new BigDecimal("12"), new BigDecimal("33.00"), BigDecimal.ZERO);

        // 截至 07-05 只有第一批 10 股可卖，第二批在途（07-08 结算）不进可卖
        assertThat(r.sellableQty()).isEqualByComparingTo("10");
        assertThat(r.shortfallQty()).isEqualByComparingTo("2");
        assertThat(r.unsettled()).hasSize(1);
        assertThat(r.unsettled().get(0).quantity()).isEqualByComparingTo("5");
    }

    @Test
    void invalidInputRejectedAndSettlementBeforeTradeDateRejected() {
        assertThatThrownBy(() -> trials.trial(acc, STK,
                LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-09"),
                new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结算日不能早于业务日");
        assertThatThrownBy(() -> trials.trial(acc, STK,
                LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-11"),
                new BigDecimal("0"), new BigDecimal("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("卖出股数必须为正");
    }
}
