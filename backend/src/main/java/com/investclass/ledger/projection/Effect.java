package com.investclass.ledger.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 由不可变事件派生出的确定性“效应”。效应是投影的最小事务单元：
 * 每个效应在一个数据库事务内提交并写入检查点，游标随检查点原子推进。
 * 崩溃后重放时已提交的效应凭 effectKey 跳过，因此不会重复增加股份或分红。
 *
 * @param qty    股份数量（股数精度；在途成交已按拆股比例折算为结算时单位）
 * @param amount 现金金额（现金精度；买/卖为含佣金前的总额）
 * @param price  每股价格（价格精度；在途成交已按拆股比例复权）
 * @param fee    佣金等费用（现金精度，独立列示）
 * @param ratio  携带的拆股比例（SPLIT_ADJUST 使用；成交效应为在途拆股连乘积）
 */
public record Effect(
        String effectKey,
        long eventId,
        LocalDate effectiveDate,
        String stage,
        Kind kind,
        String instrument,
        BigDecimal qty,
        BigDecimal amount,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal ratio,
        List<String> targetLotKeys) {

    public enum Kind {
        BUY_SETTLE, SELL_SETTLE,
        SPLIT_ADJUST,
        DIVIDEND_ENTITLE, DIVIDEND_PAY,
        RIGHTS_ENTITLE, RIGHTS_PAY, RIGHTS_ALLOT
    }

    public static String key(long eventId, String stage) {
        return "E" + eventId + ":" + stage;
    }
}
