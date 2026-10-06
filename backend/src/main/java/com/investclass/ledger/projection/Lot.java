package com.investclass.ledger.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 成本批次（FIFO）。
 *
 * totalCost / remainingCost 是现金精度的事实值；unitCost 仅展示用，
 * 不参与反推，避免多次结转舍入漂移。拆股只缩放数量并按比例切分剩余成本，
 * 整股与零碎股拆成两条批次（零碎股 fractional=true 单列）。
 *
 * @param derivedFromLotKey 拆股衍生批次的来源批次键（溯源：批次来源链）
 * @param adjustedByEventId 最近一次调整该批次的拆股事件
 */
public record Lot(
        String lotKey,
        Long openingEventId,
        String sourceEventType,
        String instrument,
        LocalDate acquiredDate,
        BigDecimal openQty,
        BigDecimal remainingQty,
        BigDecimal totalCost,
        BigDecimal remainingCost,
        BigDecimal unitCost,
        boolean fractional,
        boolean closed,
        String derivedFromLotKey,
        Long adjustedByEventId) {

    public static Lot open(String key, long eventId, String sourceType, String instrument,
                           LocalDate acquiredDate, BigDecimal qty, BigDecimal totalCost,
                           BigDecimal unitCost, boolean fractional) {
        return new Lot(key, eventId, sourceType, instrument, acquiredDate, qty, qty,
                totalCost, totalCost, unitCost, fractional, false, null, null);
    }

    public boolean isOpen() {
        return !closed && remainingQty.signum() > 0;
    }
}
