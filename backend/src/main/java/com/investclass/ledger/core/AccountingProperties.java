package com.investclass.ledger.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.RoundingMode;

/**
 * 股数、价格、现金/金额各自独立的精度与舍入策略。
 * 批次单位成本单独保留较高精度，避免 FIFO 多次结转产生累计偏差。
 */
@ConfigurationProperties(prefix = "accounting")
public record AccountingProperties(Scale scale, Rounding rounding) {

    public AccountingProperties {
        if (scale == null) scale = new Scale(8, 6, 2, 6);
        if (rounding == null)
            rounding = new Rounding(RoundingMode.HALF_UP, RoundingMode.HALF_UP, RoundingMode.HALF_UP);
    }

    public record Scale(int shares, int price, int cash, int lotCost) {
    }

    public record Rounding(RoundingMode shares, RoundingMode price, RoundingMode cash) {
    }

    public static AccountingProperties defaults() {
        return new AccountingProperties(null, null);
    }
}
