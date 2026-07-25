package com.vokerg.voktrader.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TickMath {
    private static final BigDecimal MIN_PRICE = BigDecimal.ZERO;
    private static final BigDecimal MAX_PRICE = BigDecimal.ONE;

    private TickMath() {
    }

    public static BigDecimal parseTick(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tick size is required");
        }
        try {
            return requireTick(new BigDecimal(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid tick size: " + value, e);
        }
    }

    public static BigDecimal requireTick(BigDecimal tickSize) {
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0 || tickSize.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("tick size must be between 0 and 1");
        }
        return canonical(tickSize);
    }

    public static boolean isValidPrice(BigDecimal price, BigDecimal tickSize) {
        if (price == null || price.compareTo(MIN_PRICE) <= 0 || price.compareTo(MAX_PRICE) >= 0) {
            return false;
        }
        BigDecimal tick = requireTick(tickSize);
        return canonical(price).remainder(tick).compareTo(BigDecimal.ZERO) == 0;
    }

    public static BigDecimal round(BigDecimal price, BigDecimal tickSize, TickRounding rounding) {
        if (price == null) {
            throw new IllegalArgumentException("price is required");
        }
        BigDecimal tick = requireTick(tickSize);
        TickRounding mode = rounding == null ? TickRounding.EXACT : rounding;
        if (mode == TickRounding.EXACT) {
            if (!isValidPrice(price, tick)) {
                throw new IllegalArgumentException("price " + canonical(price) + " is not aligned to tick " + tick);
            }
            return canonical(price);
        }
        RoundingMode decimalMode = switch (mode) {
            case FLOOR -> RoundingMode.FLOOR;
            case CEILING -> RoundingMode.CEILING;
            case HALF_UP -> RoundingMode.HALF_UP;
            case EXACT -> throw new IllegalStateException("EXACT handled above");
        };
        BigDecimal rounded = price.divide(tick, 0, decimalMode).multiply(tick);
        if (rounded.compareTo(MIN_PRICE) <= 0 || rounded.compareTo(MAX_PRICE) >= 0) {
            throw new IllegalArgumentException("rounded price must be between 0 and 1: " + rounded);
        }
        return canonical(rounded);
    }

    public static BigDecimal canonical(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}
