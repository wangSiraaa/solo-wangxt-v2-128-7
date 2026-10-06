package com.investclass.ledger.api;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.eod.EodService;
import com.investclass.ledger.eod.ReconciliationReport;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.ProjectionService;
import com.investclass.ledger.projection.store.ProjectionCursorRepository;
import com.investclass.ledger.projection.store.ProjectionReadRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 查询与核算链操作接口：时间轴、批次来源、现金权益、游标、重放。 */
@RestController
public class LedgerController {

    private final EventRepository events;
    private final ProjectionReadRepository read;
    private final ProjectionService projection;
    private final EodService eod;
    private final com.investclass.ledger.eod.SnapshotRepository snapshots;

    public LedgerController(EventRepository events, ProjectionReadRepository read,
                            ProjectionService projection, EodService eod,
                            com.investclass.ledger.eod.SnapshotRepository snapshots) {
        this.events = events;
        this.read = read;
        this.projection = projection;
        this.eod = eod;
        this.snapshots = snapshots;
    }

    /** 事件时间轴（不可变事实流）。 */
    @GetMapping("/api/accounts/{accountId}/timeline")
    public List<Event> timeline(@PathVariable String accountId,
                                @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso =
                                        org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                LocalDate from,
                                @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso =
                                        org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                LocalDate to) {
        return events.timeline(accountId, from, to);
    }

    @GetMapping("/api/accounts/{accountId}/lots")
    public List<ProjectionReadRepository.LotRow> lots(@PathVariable String accountId) {
        return read.lots(accountId);
    }

    @GetMapping("/api/accounts/{accountId}/cash")
    public Map<String, Object> cash(@PathVariable String accountId) {
        return Map.of("entries", read.cash(accountId), "balance", read.cashBalance(accountId));
    }

    @GetMapping("/api/accounts/{accountId}/entitlements")
    public List<ProjectionReadRepository.EntitlementRow> entitlements(
            @PathVariable String accountId) {
        return read.entitlements(accountId);
    }

    @GetMapping("/api/accounts/{accountId}/checkpoints")
    public List<ProjectionReadRepository.CheckpointRow> checkpoints(
            @PathVariable String accountId) {
        return read.checkpoints(accountId);
    }

    @GetMapping("/api/accounts/{accountId}/cursor")
    public ProjectionCursorRepository.Cursor cursor(@PathVariable String accountId) {
        return projection.cursor(accountId);
    }

    /** 手动续放到当前事件水位（模拟崩溃恢复后重放）。 */
    @PostMapping("/api/accounts/{accountId}/replay")
    public ProjectionService.Result replay(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "false") boolean fullRebuild) {
        return projection.projectTo(accountId, projection.currentWatermark(accountId),
                fullRebuild);
    }

    /** 日终：准备草稿（绑定水位）。 */
    @PostMapping("/api/accounts/{accountId}/eod/{date}/prepare")
    public EodService.PrepareResult prepare(@PathVariable String accountId,
                                            @PathVariable String date) {
        return eod.prepareDraft(accountId, LocalDate.parse(date));
    }

    /** 正式快照（仅加速读取；权威数据始终以事件账本重放为准）。 */
    @GetMapping("/api/accounts/{accountId}/eod/{date}/snapshot")
    public Map<String, Object> snapshot(@PathVariable String accountId,
                                        @PathVariable String date) {
        var d = LocalDate.parse(date);
        return Map.of(
                "positions", snapshots.positions(accountId, d),
                "cash", snapshots.cash(accountId, d) == null
                        ? java.util.Collections.emptyMap() : snapshots.cash(accountId, d));
    }

    /** 日终：三方守恒 + 账实核对（不发布，给复核人员看）。 */
    @PostMapping("/api/accounts/{accountId}/eod/{date}/verify")
    public ReconciliationReport verify(@PathVariable String accountId,
                                       @PathVariable String date) {
        return eod.verify(accountId, LocalDate.parse(date)).report();
    }

    /** 日终：复核确认后发布（任一不平阻止并指出最早失配事件）。 */
    @PostMapping("/api/accounts/{accountId}/eod/{date}/publish")
    public ReconciliationReport publish(@PathVariable String accountId,
                                        @PathVariable String date) {
        return eod.publish(accountId, LocalDate.parse(date));
    }
}
