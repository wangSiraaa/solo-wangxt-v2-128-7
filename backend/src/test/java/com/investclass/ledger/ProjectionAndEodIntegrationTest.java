package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.eod.EodService;
import com.investclass.ledger.eod.ReconciliationReport;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.ProjectionService;
import com.investclass.ledger.projection.store.ProjectionCursorRepository;
import com.investclass.ledger.projection.store.ProjectionReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投影崩溃恢复与日终发布端到端验收（真实 PG）：
 *  - 投影在“权益已计算、现金尚未入账”处中断后，从一致游标续放，不重复增加股份/分红；
 *  - 日终核对通过才发布；账实不平阻止发布并指出最早失配事件；
 *  - 发布期间迟到成交进入下一草稿。
 */
class ProjectionAndEodIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private ProjectionService projection;
    @Autowired private ProjectionReadRepository read;
    @Autowired private ProjectionCursorRepository cursors;
    @Autowired private EodService eod;
    @Autowired private ObjectMapper mapper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private com.investclass.ledger.ledger.EventAdmissionRepository admissions;

    private static final String ACC = "PA1";
    private static final String STK = "AAA";

    private Event trade(long id, String date, String settle, String side,
                        String qty, String price) {
        return new Event(null, EventType.TRADE, ACC, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "file", "T" + id, null, false, null);
    }

    private Event dividend(long id, String ex, String record, String pay, String perShare) {
        return new Event(null, EventType.CASH_DIVIDEND, ACC, STK, LocalDate.parse(ex),
                null, LocalDate.parse(record), LocalDate.parse(pay), null,
                new EventPayload.CashDividend(new BigDecimal(perShare), "CNY"),
                "file", "D" + id, null, false, null);
    }

    private long insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("file|" + e.canonicalFingerprint());
        return events.insertIfAbsent(e, json, idem).event().id();
    }

    @Test
    void crashBetweenEntitlementAndCashPaymentResumesExactlyOnce() throws Exception {
        long buyId = insert(trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));
        long divId = insert(dividend(2, "2026-09-25", "2026-09-26", "2026-09-30", "0.5"));

        long watermark = events.maxEventId(ACC);
        // 第一次投影：人为只跑到登记日（权益已计算，支付日未到），通过直接投影到水位后，
        // 模拟“现金尚未入账”：删除支付现金行并把游标回退到资格效应（模拟崩溃点）。
        projection.projectTo(ACC, watermark, true);

        List<ProjectionReadRepository.CashRow> before = read.cash(ACC);
        long dividendRowsBefore = before.stream()
                .filter(c -> c.category().equals("DIVIDEND")).count();
        assertThat(dividendRowsBefore).isEqualTo(1);

        // 模拟崩溃在权益已计算、现金尚未入账：移除支付现金行和支付检查点，游标回到资格
        jdbcUpdate("DELETE FROM projection_cash_entry WHERE account_id='%s' AND category='DIVIDEND'"
                .formatted(ACC));
        jdbcUpdate(("DELETE FROM projection_checkpoint WHERE account_id='%s' "
                + "AND effect_key='E%d:PAY'").formatted(ACC, divId));
        jdbcUpdate(("UPDATE projection_entitlement SET status='CALCULATED' "
                + "WHERE account_id='%s' AND event_id=%d").formatted(ACC, divId));
        jdbcUpdate(("UPDATE projection_cursor SET last_event_id=%d, last_stage='DIVIDEND_ENTITLE',"
                + " last_effect_key='E%d:ENTITLE' WHERE account_id='%s'")
                .formatted(buyId, divId, ACC));

        // 从一致游标重放：只能补出一条分红现金行，股份批次数量不变
        ProjectionService.Result resumed = projection.projectTo(ACC, watermark, false);
        assertThat(resumed.effectsApplied()).isGreaterThanOrEqualTo(1);

        long dividendRowsAfter = read.cash(ACC).stream()
                .filter(c -> c.category().equals("DIVIDEND")).count();
        assertThat(dividendRowsAfter).isEqualTo(1);

        var lots = read.lots(ACC);
        BigDecimal totalQty = lots.stream().map(ProjectionReadRepository.LotRow::remainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQty).isEqualByComparingTo("100");

        // 再重放一次依然幂等：不重复增加股份或分红
        projection.projectTo(ACC, watermark, false);
        long dividendRowsThird = read.cash(ACC).stream()
                .filter(c -> c.category().equals("DIVIDEND")).count();
        assertThat(dividendRowsThird).isEqualTo(1);
        BigDecimal totalQtyAgain = read.lots(ACC).stream()
                .map(ProjectionReadRepository.LotRow::remainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQtyAgain).isEqualByComparingTo("100");
    }

    @Test
    void eodPublishBalancesThenBlocksOnExternalMismatchAndPointsEarliestEvent()
            throws Exception {
        insert(trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));

        LocalDate day = LocalDate.parse("2026-09-30");
        var prep = eod.prepareDraft(ACC, day);
        assertThat(prep.watermark()).isGreaterThan(0L);

        // 无对账单：内部三方守恒通过，可以发布
        ReconciliationReport ok = eod.publish(ACC, day);
        assertThat(ok.allBalanced()).isTrue();

        // 另一个账户演示账实不平被阻止
        String acc2 = "PA2";
        Event badBuy = new Event(null, EventType.TRADE, acc2, STK,
                LocalDate.parse("2026-09-23"), LocalDate.parse("2026-09-24"), null, null,
                null, new EventPayload.Trade("BUY", new BigDecimal("100"),
                        new BigDecimal("10"), BigDecimal.ZERO, "CNY"),
                "file", "T-BAD-1", null, false, null);
        long badId = insert(badBuy);
        eod.prepareDraft(acc2, day);

        // 外部对账单声称持有 90 股（账面 100）
        jdbcUpdate(("INSERT INTO external_statement "
                + "(account_id,business_date,instrument,external_qty,external_fractional_qty,"
                + "external_cost) VALUES ('%s','%s','%s',90,0,900.00) ON CONFLICT DO NOTHING")
                .formatted(acc2, day, STK));

        assertThatThrownBy(() -> eod.publish(acc2, day))
                .isInstanceOf(EodService.PublishBlockedException.class)
                .hasMessageContaining("book vs external mismatch");
        var draft = eod.verify(acc2, day).report();
        assertThat(draft.bookVsExternalBalanced()).isFalse();
        assertThat(draft.earliestMismatchEventId()).isEqualTo(Long.valueOf(badId));
    }

    @Test
    void lateTradeDuringPublishGoesToNextDraft() throws Exception {
        insert(trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));
        LocalDate day = LocalDate.parse("2026-09-30");
        eod.prepareDraft(ACC, day);
        ReconciliationReport published = eod.publish(ACC, day);
        assertThat(published.allBalanced()).isTrue();

        // 发布期间迟到一笔业务日仍在 9/30 前的成交
        Event late = trade(2, "2026-09-29", "2026-09-30", "BUY", "50", "9");
        insert(late);

        // 对当日再次执行 publish 流程：冻结水位不变 -> 迟到被登记并进入下一草稿
        ReconciliationReport again = eod.publish(ACC, day);
        assertThat(again.allBalanced()).isTrue();
        ProjectionCursorRepository.Cursor cur = cursors.get(ACC);
        assertThat(cur.lastEventId()).isGreaterThan(0);

        // 快照正式视图仍然是发布时的 100 股（不被迟到事件反向改写）
        BigDecimal snapshotQty = readJdbcBigDecimal(
                "SELECT qty FROM snapshot_position WHERE account_id='%s' AND business_date='%s'"
                        .formatted(ACC, day));
        assertThat(snapshotQty).isEqualByComparingTo("100");

        // 下一草稿（次日）水位包含迟到事件
        LocalDate next = day.plusDays(1);
        var nextPrep = eod.prepareDraft(ACC, next);
        assertThat(nextPrep.lateEventIds()).isNotEmpty();
    }

    private void jdbcUpdate(String sql) {
        jdbcTemplate.execute(sql);
    }

    private BigDecimal readJdbcBigDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
