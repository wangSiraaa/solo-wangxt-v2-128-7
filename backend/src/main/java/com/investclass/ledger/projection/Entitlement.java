package com.investclass.ledger.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 权益资格行：登记日 CALCULATED，支付日 PAID/SUBSCRIBED。 */
public record Entitlement(
        Long eventId,
        String instrument,
        String kind,
        LocalDate recordDate,
        LocalDate paymentDate,
        BigDecimal eligibleQty,
        BigDecimal amountPerShare,
        BigDecimal grossAmount,
        String status,
        BigDecimal subscribedQty,
        String idemKey) {

    public Entitlement withStatus(String s, BigDecimal subscribed) {
        return new Entitlement(eventId, instrument, kind, recordDate, paymentDate, eligibleQty,
                amountPerShare, grossAmount, s, subscribed, idemKey);
    }
}
