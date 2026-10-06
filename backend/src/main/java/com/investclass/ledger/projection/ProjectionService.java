package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.MoneyMath;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.store.ProjectionCursorRepository;
import com.investclass.ledger.projection.store.ProjectionWriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 投影服务：事件账本 -> 派生账本（批次/现金/权益/检查点/游标）。
 *
 * 重放策略：从空 FoldState 开始，按规范顺序折叠到事件水位，但已存在检查点的效应
 * 直接从派生表已提交状态继续 —— 实际实现采用“逐效应事务 + effectKey 幂等”：
 * 每个效应在同一事务内写派生表、写检查点、推进游标。事务提交前崩溃则三写皆无，
 * 重放到同一 effectKey 时所有 upsert 幂等、现金唯一键阻止重复分红。
 */
@Service
public class ProjectionService {

    private final EventRepository events;
    private final ProjectionWriteRepository writes;
    private final ProjectionCursorRepository cursors;
    private final AccountingProperties props;
    private final TransactionTemplate effectTx;

    public ProjectionService(EventRepository events, ProjectionWriteRepository writes,
                             ProjectionCursorRepository cursors, AccountingProperties props,
                             PlatformTransactionManager txManager) {
        this.events = events;
        this.writes = writes;
        this.cursors = cursors;
        this.props = props;
        this.effectTx = new TransactionTemplate(txManager);
        this.effectTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Result(long watermark, int effectsApplied, int effectsSkipped) {
    }

    /**
     * 重放到某业务日（用于草稿/发布）：按业务日窗口折叠，游标仅记录进度。
     * 已发布日的迟到成交只能进入下一草稿，不能反向改写正式视图。
     */
    public synchronized Result projectForDate(String accountId, LocalDate date,
                                              boolean fullRebuild) {
        if (fullRebuild) {
            writes.clearAccountProjections(accountId);
        }
        List<Event> window = events.findByAccountForDate(accountId, date);
        long watermark = window.stream().mapToLong(Event::id).max().orElse(0L);
        return projectWindow(accountId, window, watermark, fullRebuild);
    }

    /** 按事件 id 水位推进（崩溃恢复 / 增量投影使用）。 */
    public synchronized Result projectTo(String accountId, long watermark,
                                         boolean fullRebuild) {
        if (fullRebuild) {
            writes.clearAccountProjections(accountId);
        }
        List<Event> window = events.findByAccountUpTo(accountId, watermark);
        return projectWindow(accountId, window, watermark, fullRebuild);
    }

    private synchronized Result projectWindow(String accountId, List<Event> window,
                                              long watermark, boolean fullRebuild) {
        ProjectionCursorRepository.Cursor cur = cursors.get(accountId);
        long fromId = fullRebuild ? 0L : cur.lastEventId();
        Map<Long, Event> byId = window.stream()
                .collect(Collectors.toMap(Event::id, e -> e));
        List<Effect> planned = EffectPlanner.plan(window).effects();

        var existingKeys = writes.checkpointEffectKeys(accountId);
        java.util.Set<String> done = new java.util.HashSet<>(existingKeys);

        int applied = 0;
        int skipped = 0;
        FoldState state = new FoldState(props);
        // 折叠始终从头计算，用于指纹与余额核对；只有未检查点的效应才写库。
        for (Effect eff : planned) {
            Event src = byId.get(eff.eventId());
            FoldEngine.apply(state, eff, src, accountId);
            if (eff.eventId() <= fromId && done.contains(eff.effectKey())) {
                skipped++;
                continue;
            }
            if (done.contains(eff.effectKey())) {
                skipped++;
                continue;
            }
            // 每效应一个事务：派生写入 + 检查点 + 游标推进原子提交。
            // 崩溃在“权益已计算、现金尚未入账”时，未提交现金与未前进游标一起回滚。
            effectTx.executeWithoutResult(status ->
                    applyEffect(accountId, state, eff, src));
            done.add(eff.effectKey());
            applied++;
        }
        LocalDate lastDate = window.isEmpty() ? null
                : window.get(window.size() - 1).businessDate();
        long wm = watermark;
        effectTx.executeWithoutResult(s ->
                cursors.advance(accountId, wm, lastDate, "WATERMARK", "WM" + wm));
        return new Result(watermark, applied, skipped);
    }

    /**
     * 单效应写入（运行在 REQUIRES_NEW 事务内）：派生表 + 检查点 + 游标同生共死。
     */
    void applyEffect(String accountId, FoldState stateAfter, Effect eff, Event src) {
        switch (eff.kind()) {
            case SELL_SETTLE -> {
                // 该卖出产生的所有结转与批次变更
                for (var e : stateAfter.consumptions.entrySet()) {
                    if (e.getKey().startsWith(eff.eventId() + ":")) {
                        writes.addConsumption(accountId, e.getValue());
                    }
                }
                for (String lotKey : stateAfter.changedLotKeys) {
                    writes.upsertLot(accountId, stateAfter.lots.get(lotKey));
                }
            }
            case SPLIT_ADJUST -> {
                for (String lotKey : stateAfter.changedLotKeys) {
                    writes.upsertLot(accountId, stateAfter.lots.get(lotKey));
                }
            }
            case BUY_SETTLE, RIGHTS_ALLOT -> {
                for (String lotKey : stateAfter.changedLotKeys) {
                    writes.upsertLot(accountId, stateAfter.lots.get(lotKey));
                }
            }
            case DIVIDEND_ENTITLE, RIGHTS_ENTITLE -> {
                Entitlement en = stateAfter.entitlements.get("E" + eff.eventId() + ":ENTITLE");
                if (en != null) {
                    writes.upsertEntitlement(accountId, en);
                }
            }
            case DIVIDEND_PAY, RIGHTS_PAY -> {
                Entitlement en = stateAfter.entitlements.get("E" + eff.eventId() + ":ENTITLE");
                if (en != null) {
                    writes.upsertEntitlement(accountId, en);
                }
            }
            default -> {
            }
        }
        // 该效应产生的现金行（现金唯一键保证不重复入账）
        for (CashEntry c : stateAfter.cash.values()) {
            if (c.effectKey().equals(eff.effectKey())) {
                writes.addCash(accountId, c);
            }
        }

        BigDecimal openCost = FoldEngine.positions(stateAfter).stream()
                .map(FoldEngine.PositionView::openCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String sharesHash = sharesFingerprint(stateAfter);
        writes.checkpoint(accountId, src.businessDate(), eff.stage(), eff.eventId(),
                eff.effectKey(), sharesHash, FoldEngine.cashBalance(stateAfter),
                MoneyMath.cash(openCost, props), stateAfter.realizedPnl);
        cursors.advance(accountId, eff.eventId(), src.businessDate(), eff.stage(),
                eff.effectKey());
    }

    /** 当前持仓指纹：证券->整股/零股/剩余成本，确定性字符串 SHA-256。 */
    private String sharesFingerprint(FoldState s) {
        StringBuilder sb = new StringBuilder();
        var positions = FoldEngine.positions(s);
        for (var p : positions) {
            sb.append(p.instrument()).append('=')
                    .append(p.qty().toPlainString()).append('+')
                    .append(p.fractionalQty().toPlainString()).append('@')
                    .append(p.openCost().toPlainString()).append(';');
        }
        return Idempotency.sha256Hex(sb.toString());
    }

    /** 供发布/调试：读取某账户游标。 */
    public ProjectionCursorRepository.Cursor cursor(String accountId) {
        return cursors.get(accountId);
    }

    public long currentWatermark(String accountId) {
        return events.maxEventId(accountId);
    }

    public LocalDate watermarkBusinessDate(String accountId, long watermark) {
        return events.findByAccountUpTo(accountId, watermark).stream()
                .map(Event::businessDate).max(LocalDate::compareTo).orElse(null);
    }
}
