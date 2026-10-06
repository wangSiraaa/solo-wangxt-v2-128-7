package com.investclass.ledger.eod;

import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.ledger.EventAdmissionRepository;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.FoldEngine;
import com.investclass.ledger.projection.FoldState;
import com.investclass.ledger.projection.ProjectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 日终发布：
 *  1. 关闭窗口：水位 = business_date <= 当日 的最大事件 id；水位之后到达、
 *     却归属当日的成交标记 late_flag，滚入下一草稿，不污染当日正式视图。
 *  2. 重放投影到水位（崩溃从一致游标续放）。
 *  3. 纯函数三方守恒 + 账实核对，任一不平则草稿 BLOCKED 且带最早失配事件，阻止发布。
 *  4. 复核人员显式确认后才生成正式快照；快照只加速，始终可由事件重放复算。
 */
@Service
public class EodService {

    private final EventRepository events;
    private final EventAdmissionRepository admissions;
    private final ProjectionService projection;
    private final EodDraftRepository drafts;
    private final ExternalStatementRepository external;
    private final AccountingProperties props;

    public EodService(EventRepository events, EventAdmissionRepository admissions,
                      ProjectionService projection,
                      EodDraftRepository drafts, ExternalStatementRepository external,
                      AccountingProperties props) {
        this.events = events;
        this.admissions = admissions;
        this.projection = projection;
        this.drafts = drafts;
        this.external = external;
        this.props = props;
    }

    public record PrepareResult(long draftId, long watermark, List<Long> lateEventIds) {
    }

    /** 建立/刷新某日草稿：已发布日维持冻结水位；否则水位=当日窗口最大事件 id。 */
    @Transactional
    public PrepareResult prepareDraft(String accountId, LocalDate date) {
        Long frozen = drafts.publishedWatermark(accountId, date);
        long watermark;
        if (frozen != null) {
            watermark = frozen;
            // 冻结后到达、业务日仍 <= 当日的成交全部归为迟到，进入次日草稿
            List<Event> arrivals = events.lateArrivalsAfter(accountId, watermark, date);
            for (Event e : arrivals) {
                admissions.markLate(e.id(), accountId, date.plusDays(1));
            }
        } else {
            List<Event> window = events.findByAccountForDate(accountId, date);
            watermark = window.stream().mapToLong(Event::id).max().orElse(0L);
        }
        // 归属到本草稿日的迟到事件（前一日发布窗口后到达、归属当日）
        List<Long> late = admissions.lateEventIds(accountId, date);
        long draftId = drafts.createDraft(accountId, date, watermark, late);
        return new PrepareResult(draftId, watermark, late);
    }

    public record VerifyResult(ReconciliationReport report, EodDraftRepository.Draft draft) {
    }

    /** 运行核对并把结果落到草稿（不发布）。复核人员据此确认三方守恒。 */
    public VerifyResult verify(String accountId, LocalDate date) {
        EodDraftRepository.Draft draft = drafts.find(accountId, date);
        if (draft == null) {
            draft = reload(accountId, date);
        }
        projection.projectForDate(accountId, date, false);

        List<Event> window = events.findByAccountForDate(accountId, date);
        List<Reconciliator.ExternalStatement> stmt = external.find(accountId, date);
        ReconciliationReport report = Reconciliator.reconcile(accountId, date,
                draft.watermark(), window, stmt, props);

        boolean all = report.qtyBalanced() && report.cashBalanced()
                && report.costBalanced() && report.bookVsExternalBalanced();
        drafts.markResult(draft.id(), report.qtyBalanced(), report.cashBalanced(),
                report.costBalanced(), report.earliestMismatchEventId(),
                report.mismatchDetail(), all ? "DRAFT" : "BLOCKED");
        return new VerifyResult(report, drafts.find(accountId, date));
    }

    private EodDraftRepository.Draft reload(String accountId, LocalDate date) {
        PrepareResult pr = prepareDraft(accountId, date);
        return drafts.find(accountId, date);
    }

    public static class PublishBlockedException extends RuntimeException {
        public final long earliestMismatchEventId;

        public PublishBlockedException(String message, long eventId) {
            super(message);
            this.earliestMismatchEventId = eventId;
        }
    }

    /**
     * 复核确认后的正式发布：
     *  - 再次冻结水位并复算核对（防止复核后又有事件进入）；
     *  - 任一不平抛 {@link PublishBlockedException}，指出最早失配事件；
     *  - 通过则写正式快照并把草稿置 PUBLISHED，发布期间的迟到成交进入下一草稿。
     */
    @Transactional
    public ReconciliationReport publish(String accountId, LocalDate date) {
        EodDraftRepository.Draft draft = drafts.find(accountId, date);
        if (draft == null) {
            throw new IllegalStateException("draft not prepared for " + date);
        }
        // 已发布日永远使用首次发布冻结的水位（快照不可被迟到事件反向改写）
        Long frozen = drafts.publishedWatermark(accountId, date);
        long frozenWatermark = frozen != null ? frozen : draft.watermark();
        // 冻结水位之后到达、业务日仍 <= 当日的事件属于发布期间迟到。
        // 先登记准入（事件本体不可变），它们绝不参与本次核对/快照。
        List<Event> arrivalsDuringPublish = events.lateArrivalsAfter(accountId,
                frozenWatermark, date);
        for (Event e : arrivalsDuringPublish) {
            admissions.markLate(e.id(), accountId, date.plusDays(1));
        }

        List<Event> window = events.findByAccountForDate(accountId, date).stream()
                .filter(e -> e.id() <= frozenWatermark)
                .toList();
        // 物化派生账本到冻结水位（id 口径，不含迟到事件）；崩溃从一致游标续放
        projection.projectTo(accountId, frozenWatermark, false);
        List<Reconciliator.ExternalStatement> stmt = external.find(accountId, date);
        ReconciliationReport report = Reconciliator.reconcile(accountId, date,
                frozenWatermark, window, stmt, props);
        if (!report.allBalanced()) {
            drafts.markResult(draft.id(), report.qtyBalanced(), report.cashBalanced(),
                    report.costBalanced(), report.earliestMismatchEventId(),
                    report.mismatchDetail(), "BLOCKED");
            throw new PublishBlockedException(
                    "EOD publish blocked at " + date + ": " + report.mismatchDetail(),
                    report.earliestMismatchEventId() == null ? -1
                            : report.earliestMismatchEventId());
        }

        writeSnapshots(accountId, date, window, frozenWatermark);
        drafts.markResult(draft.id(), true, true, true, null, null, "PUBLISHED");
        drafts.publish(draft.id());
        drafts.recordPublishedWatermark(accountId, date, frozenWatermark);

        if (!arrivalsDuringPublish.isEmpty()) {
            long headNow = events.maxEventId(accountId);
            drafts.createDraft(accountId, date.plusDays(1), headNow,
                    arrivalsDuringPublish.stream().map(Event::id).toList());
        }
        return report;
    }

    private void writeSnapshots(String accountId, LocalDate date, List<Event> window,
                                long watermark) {
        FoldState state = new FoldState(props);
        var planned = com.investclass.ledger.projection.EffectPlanner.plan(window);
        java.util.Map<Long, Event> byId = new java.util.LinkedHashMap<>();
        window.forEach(e -> byId.put(e.id(), e));
        com.investclass.ledger.projection.FoldEngine.applyAll(state, planned.effects(),
                byId, accountId);

        for (FoldEngine.PositionView p : FoldEngine.positions(state)) {
            BigDecimal realized = state.realizedPnl;
            drafts.upsertSnapshotPosition(accountId, date, p.instrument(), p.qty(),
                    p.fractionalQty(), p.openCost(), p.avgCost(), realized, watermark);
        }
        for (var lot : state.lots.values()) {
            if (lot.isOpen()) {
                drafts.upsertSnapshotLot(accountId, date, lot);
            }
        }
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;
        for (var c : state.cash.values()) {
            if (c.direction().equals("IN")) {
                inflow = inflow.add(c.amount());
            } else {
                outflow = outflow.add(c.amount());
            }
        }
        drafts.upsertSnapshotCash(accountId, date, inflow, outflow,
                FoldEngine.cashBalance(state), watermark);
    }
}
