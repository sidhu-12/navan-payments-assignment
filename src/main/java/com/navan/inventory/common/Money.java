package com.navan.inventory.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * Invoice and transaction amounts use DECIMAL(19,2). Never add IEEE floats.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = of(BigDecimal.ZERO);

    private Money() {}

    public static BigDecimal of(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String value) {
        return of(new BigDecimal(value));
    }

    public static BigDecimal sum(Collection<BigDecimal> amounts) {
        BigDecimal total = ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(of(Objects.requireNonNull(amount, "amount")));
        }
        return total;
    }
}
