package com.investclass.ledger.importing;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;

/**
 * 统一的成交/公司行动导入行（CSV 列）：
 * <pre>
 * rowType      TRADE|STOCK_SPLIT|CASH_DIVIDEND|RIGHTS_OFFER
 * accountId, instrument
 * businessDate 交易日 / 除权日
 * settlementDate, recordDate, paymentDate, allotmentDate
 * side         BUY|SELL（仅 TRADE）
 * quantity, price, commission（TRADE）
 * ratio（STOCK_SPLIT）
 * amountPerShare（CASH_DIVIDEND）
 * rightsPerShare, subscriptionPrice, subscribedQty（RIGHTS_OFFER）
 * currency
 * sourceKey    来源系统原始键；为空时按规范化指纹生成
 * </pre>
 */
public record ImportRow(
        String rowType,
        String accountId,
        String instrument,
        String businessDate,
        String settlementDate,
        String recordDate,
        String paymentDate,
        String allotmentDate,
        String side,
        String quantity,
        String price,
        String commission,
        String ratio,
        String amountPerShare,
        String rightsPerShare,
        String subscriptionPrice,
        String subscribedQty,
        String currency,
        String sourceKey) {

    /** 规范化来源键：显式 sourceKey 优先；否则由业务字段拼成稳定键。 */
    public String canonicalSourceKey() {
        if (sourceKey != null && !sourceKey.isBlank()) {
            return sourceKey.trim();
        }
        return String.join("~", nz(rowType), nz(accountId), nz(instrument), nz(businessDate),
                nz(settlementDate), nz(side), nz(quantity), nz(price), nz(ratio),
                nz(amountPerShare), nz(rightsPerShare), nz(subscriptionPrice),
                nz(subscribedQty), nz(recordDate), nz(paymentDate), nz(allotmentDate));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    public Event toEvent(String sourceSystem) {
        EventType type = EventType.valueOf(rowType.trim().toUpperCase());
        EventPayload payload = switch (type) {
            case TRADE -> new EventPayload.Trade(side.trim(), decimal(quantity), decimal(price),
                    decimalOrDefault(commission, "0"), currency);
            case STOCK_SPLIT -> new EventPayload.StockSplit(decimal(ratio));
            case CASH_DIVIDEND -> new EventPayload.CashDividend(decimal(amountPerShare), currency);
            case RIGHTS_OFFER -> new EventPayload.RightsOffer(decimal(rightsPerShare),
                    decimal(subscriptionPrice),
                    decimalOrDefault(subscribedQty, "0"), currency);
        };
        return new Event(null, type, accountId.trim(), instrument.trim(),
                java.time.LocalDate.parse(businessDate),
                dateOrNull(settlementDate), dateOrNull(recordDate),
                dateOrNull(paymentDate), dateOrNull(allotmentDate),
                payload, sourceSystem, canonicalSourceKey(), null, false, null);
    }

    private static java.math.BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return new java.math.BigDecimal(s.trim());
    }

    private static java.math.BigDecimal decimalOrDefault(String s, String fallback) {
        java.math.BigDecimal v = decimal(s);
        return v == null ? new java.math.BigDecimal(fallback) : v;
    }

    private static java.time.LocalDate dateOrNull(String s) {
        return s == null || s.isBlank() ? null : java.time.LocalDate.parse(s.trim());
    }
}
