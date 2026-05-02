package com.vokerg.voktrader.trade;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@Primary
public class PolymarketFeeCalculator {
    private static final int MONEY_SCALE = 8;

    public BigDecimal estimateTakerFeeUsd(BigDecimal shares, BigDecimal price, BigDecimal feeRate) {
        if (shares == null || price == null || feeRate == null) {
            return zero();
        }
        if (shares.compareTo(BigDecimal.ZERO) <= 0
                || price.compareTo(BigDecimal.ZERO) <= 0
                || price.compareTo(BigDecimal.ONE) >= 0
                || feeRate.compareTo(BigDecimal.ZERO) <= 0) {
            return zero();
        }
        return shares
                .multiply(feeRate)
                .multiply(price)
                .multiply(BigDecimal.ONE.subtract(price))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
