package com.investclass.ledger.importing;

/** 读取一行 + 批次来源，处理器输出待登记事件。 */
public record Registration(ImportRow row, String sourceSystem, String payloadJson,
                           String idempotencyKey) {
}
