package com.investclass.ledger.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;

/**
 * 事件载荷密封类型。统一以 JSONB 落盘，回放时原样还原。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EventPayload.Trade.class, name = "TRADE"),
        @JsonSubTypes.Type(value = EventPayload.StockSplit.class, name = "STOCK_SPLIT"),
        @JsonSubTypes.Type(value = EventPayload.CashDividend.class, name = "CASH_DIVIDEND"),
        @JsonSubTypes.Type(value = EventPayload.RightsOffer.class, name = "RIGHTS_OFFER")
})
public sealed interface EventPayload
        permits EventPayload.Trade, EventPayload.StockSplit,
        EventPayload.CashDividend, EventPayload.RightsOffer {

    String canonical();

    default Trade asTrade() {
        return (Trade) this;
    }

    default StockSplit asSplit() {
        return (StockSplit) this;
    }

    default CashDividend asDividend() {
        return (CashDividend) this;
    }

    default RightsOffer asRights() {
        return (RightsOffer) this;
    }

    record Trade(String side, BigDecimal quantity, BigDecimal price,
                 BigDecimal commission, String currency) implements EventPayload {
        public boolean isBuy() {
            return "BUY".equalsIgnoreCase(side);
        }

        @Override
        public String canonical() {
            return "T:" + side.toUpperCase() + ":" + quantity.stripTrailingZeros().toPlainString()
                    + ":" + price.stripTrailingZeros().toPlainString()
                    + ":" + (commission == null ? "0" : commission.stripTrailingZeros().toPlainString())
                    + ":" + currency;
        }
    }

    /** ratio = 新股/旧股。1 拆 3 → 3；10 合 1 → 0.1。 */
    record StockSplit(BigDecimal ratio) implements EventPayload {
        @Override
        public String canonical() {
            return "S:" + ratio.stripTrailingZeros().toPlainString();
        }
    }

    record CashDividend(BigDecimal amountPerShare, String currency) implements EventPayload {
        @Override
        public String canonical() {
            return "D:" + amountPerShare.stripTrailingZeros().toPlainString() + ":" + currency;
        }
    }

    /**
     * @param rightsPerShare   每股可获配股权利（如 0.1 表示每 10 股可认购 1 股）
     * @param subscriptionPrice 认购价（支付日扣款）
     * @param subscribedQty    实际认购数量（部分认购时 &lt; eligibleRights，可为零碎）
     */
    record RightsOffer(BigDecimal rightsPerShare, BigDecimal subscriptionPrice,
                       BigDecimal subscribedQty, String currency) implements EventPayload {
        @Override
        public String canonical() {
            return "R:" + rightsPerShare.stripTrailingZeros().toPlainString()
                    + ":" + subscriptionPrice.stripTrailingZeros().toPlainString()
                    + ":" + (subscribedQty == null ? "0"
                            : subscribedQty.stripTrailingZeros().toPlainString())
                    + ":" + currency;
        }
    }
}
