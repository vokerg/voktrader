package com.vokerg.voktrader.fee;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

@Component
public final class UnifiedFeeModel implements FeeModel {
    public static final String VERSION = "polymarket-fee-v1";
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
    private static final int OUTPUT_SCALE = 6;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public BigDecimal calculate(
            FeeMetadata metadata,
            FeeLiquidityRole role,
            BigDecimal price,
            BigDecimal shares
    ) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(role, "role");
        requireProbability(price);
        requirePositive(shares, "shares");

        if (metadata.takerOnly() && role == FeeLiquidityRole.MAKER) {
            return BigDecimal.ZERO.setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal probabilityWeight = price
                .multiply(BigDecimal.ONE.subtract(price), MATH_CONTEXT)
                .pow(metadata.exponent(), MATH_CONTEXT);
        return shares
                .multiply(metadata.rate(), MATH_CONTEXT)
                .multiply(probabilityWeight, MATH_CONTEXT)
                .setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    private static void requireProbability(BigDecimal price) {
        Objects.requireNonNull(price, "price");
        if (price.signum() <= 0 || price.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("price must be strictly between 0 and 1");
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
