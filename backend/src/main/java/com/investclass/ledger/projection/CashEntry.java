package com.investclass.ledger.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 现金效应（结算日/支付日才入账）。idemKey = effectKey+category，保证崩溃重放不重复记账。 */
public record CashEntry(
        Long eventId,
        LocalDate businessDate,
        LocalDate valueDate,
        String effectKey,
        String direction,
        BigDecimal amount,
        String category,
        String idemKey) {
}
