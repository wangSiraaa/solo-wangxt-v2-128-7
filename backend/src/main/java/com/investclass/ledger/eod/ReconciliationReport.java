package com.investclass.ledger.eod;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/** 三方守恒 + 账实核对的结果。任一不平衡都带“最早失配事件”。 */
public record ReconciliationReport(
        String accountId,
        java.time.LocalDate businessDate,
        long watermarkEventId,
        boolean qtyBalanced,
        boolean cashBalanced,
        boolean costBalanced,
        boolean bookVsExternalBalanced,
        Long earliestMismatchEventId,
        String mismatchDetail,
        List<Line> positions,
        BigDecimal projectedCash,
        BigDecimal externalCash) {

    @JsonProperty("allBalanced")
    public boolean allBalanced() {
        return qtyBalanced && cashBalanced && costBalanced && bookVsExternalBalanced;
    }

    public record Line(
            String instrument,
            BigDecimal projectedQty,
            BigDecimal projectedFractionalQty,
            BigDecimal projectedOpenCost,
            BigDecimal externalQty,
            BigDecimal externalFractionalQty,
            BigDecimal externalCost,
            BigDecimal externalCash,
            boolean qtyMatch,
            boolean costMatch) {
    }
}
