package com.investclass.ledger.core;

/** 业务事件类型。 */
public enum EventType {
    /** 成交：买入/卖出，交易日成交，结算日股份与现金同时交割。 */
    TRADE,
    /** 拆股（含合股，比例 &lt;1）：除权日调整，登记日确定资格股数。 */
    STOCK_SPLIT,
    /** 现金分红：登记日算资格，支付日现金才入账。 */
    CASH_DIVIDEND,
    /** 配股/供股：登记日算可认购权数，通知日告知，支付日扣款，到账日新增批次。 */
    RIGHTS_OFFER
}
