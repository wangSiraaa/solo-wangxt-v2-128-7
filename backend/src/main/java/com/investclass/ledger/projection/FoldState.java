package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投影折叠状态。投影表是派生缓存，可清空后按规范顺序重放整个重建；
 * 崩溃恢复时从最后一致游标继续。
 */
public final class FoldState {

    public final AccountingProperties props;
    /** lotKey -> Lot，LinkedHashMap 保持 FIFO 顺序（按到账日、开批事件）。 */
    public final Map<String, Lot> lots = new LinkedHashMap<>();
    public final Map<String, LotConsumption> consumptions = new LinkedHashMap<>();
    public final Map<String, CashEntry> cash = new LinkedHashMap<>();
    public final Map<String, Entitlement> entitlements = new LinkedHashMap<>();
    public BigDecimal realizedPnl = BigDecimal.ZERO;
    /** 当前 apply 的效应所变更/新增的批次键（每效应开始前清空）。 */
    public transient java.util.Set<String> changedLotKeys = new java.util.HashSet<>();

    public void putLot(String key, Lot lot) {
        lots.put(key, lot);
        changedLotKeys.add(key);
    }

    public void markChangedLot(String key) {
        changedLotKeys.add(key);
    }

    public void beginEffect() {
        changedLotKeys.clear();
    }

    public FoldState(AccountingProperties props) {
        this.props = props;
    }

    public Iterable<Lot> openLotsOf(String instrument) {
        return lots.values().stream()
                .filter(l -> l.instrument().equals(instrument) && l.isOpen())
                .toList();
    }
}
