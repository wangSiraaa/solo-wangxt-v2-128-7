package com.investclass.ledger.core;

/** 核算不变量被破坏（超卖、权益为负、成本不守恒等）。携带最早失配事件 id。 */
public class AccountingException extends RuntimeException {

    private final long eventId;

    public AccountingException(long eventId, String message) {
        super("event=" + eventId + " " + message);
        this.eventId = eventId;
    }

    public long eventId() {
        return eventId;
    }
}
