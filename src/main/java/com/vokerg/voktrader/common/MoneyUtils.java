package com.vokerg.voktrader.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtils {

    private MoneyUtils() {
    }

    public static BigDecimal scalePrice(BigDecimal value) {
        if (value == null) {
            return null;
        }

        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
