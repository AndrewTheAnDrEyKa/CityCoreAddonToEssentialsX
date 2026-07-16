package ru.citycore.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MinorUnits {
    private MinorUnits() {}
    public static long parse(String value, int scale) {
        if (value == null || !value.matches("[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException("Invalid decimal amount");
        return new BigDecimal(value).movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
    public static String format(long value, int scale) { return BigDecimal.valueOf(value, scale).toPlainString(); }
}
