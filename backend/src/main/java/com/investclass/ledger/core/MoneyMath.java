package com.investclass.ledger.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 按独立精度规整数值：股数、价格、现金互不复用精度。
 */
public final class MoneyMath {

    private MoneyMath() {
    }

    public static BigDecimal shares(BigDecimal v, AccountingProperties props) {
        return v.setScale(props.scale().shares(), props.rounding().shares());
    }

    public static BigDecimal price(BigDecimal v, AccountingProperties props) {
        return v.setScale(props.scale().price(), props.rounding().price());
    }

    public static BigDecimal cash(BigDecimal v, AccountingProperties props) {
        return v.setScale(props.scale().cash(), props.rounding().cash());
    }

    public static BigDecimal unitCost(BigDecimal v, AccountingProperties props) {
        return v.setScale(props.scale().lotCost(), RoundingMode.HALF_UP);
    }

    public static boolean isFractional(BigDecimal qty, AccountingProperties props) {
        return shares(qty, props).stripTrailingZeros().scale() > 0;
    }

    /** qty*unitCost，先相乘再按现金精度舍入，保证“数量*成本”始终守恒到分。 */
    public static BigDecimal costOf(BigDecimal qty, BigDecimal unitCost, AccountingProperties props) {
        return cash(qty.multiply(unitCost), props);
    }

    public static BigDecimal positiveOrNull(BigDecimal v) {
        return v == null ? null : (v.signum() > 0 ? v : null);
    }
}
