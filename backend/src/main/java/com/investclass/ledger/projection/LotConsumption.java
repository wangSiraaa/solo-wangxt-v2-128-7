package com.investclass.ledger.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/** FIFO 批次结转（卖出/转出），账实核对时成本守恒的来源之一。 */
public record LotConsumption(
        String lotKey,
        Long sellingEventId,
        String instrument,
        LocalDate atDate,
        BigDecimal qty,
        BigDecimal costReleased,
        BigDecimal proceeds) {
}
