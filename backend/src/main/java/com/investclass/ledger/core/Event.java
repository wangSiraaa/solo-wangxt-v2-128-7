package com.investclass.ledger.core;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 不可变业务事件。payload 按事件类型解释：
 *
 * <ul>
 *   <li>TRADE: side(BUY/SELL)、quantity、price、commission、currency</li>
 *   <li>STOCK_SPLIT: ratio（新股/旧股，例如 3 表示 1 拆 3）</li>
 *   <li>CASH_DIVIDEND: amountPerShare、currency</li>
 *   <li>RIGHTS_OFFER: rightsPerShare（每股可得权数）、subscriptionPrice、
 *       subscribedQty（实际认购权数，部分认购时小于资格权数）、allotmentDate</li>
 * </ul>
 */
public record Event(
        Long id,
        EventType type,
        String accountId,
        String instrument,
        LocalDate businessDate,
        LocalDate settlementDate,
        LocalDate recordDate,
        LocalDate paymentDate,
        LocalDate allotmentDate,
        EventPayload payload,
        String sourceSystem,
        String sourceKey,
        String idempotencyKey,
        boolean late,
        java.time.Instant ingestedAt) {

    public Event withId(long newId, String newIdempotencyKey, java.time.Instant at) {
        return new Event(newId, type, accountId, instrument, businessDate, settlementDate,
                recordDate, paymentDate, allotmentDate, payload, sourceSystem, sourceKey,
                newIdempotencyKey, late, at);
    }

    /** 用于幂等凭证的规范化指纹：同一成交/公司行动不论被哪个文件重复导入都只登记一次。 */
    public String canonicalFingerprint() {
        return String.join("|",
                type.name(),
                accountId,
                instrument,
                businessDate.toString(),
                nz(settlementDate), nz(recordDate), nz(paymentDate), nz(allotmentDate),
                payload.canonical());
    }

    private static String nz(LocalDate d) {
        return d == null ? "-" : d.toString();
    }

    public boolean isCorporateAction() {
        return type != EventType.TRADE;
    }

    public BigDecimal qtyOrNull() {
        return switch (type) {
            case TRADE -> payload.asTrade().quantity();
            case STOCK_SPLIT -> null;
            case CASH_DIVIDEND -> null;
            case RIGHTS_OFFER -> payload.asRights().subscribedQty();
        };
    }
}
