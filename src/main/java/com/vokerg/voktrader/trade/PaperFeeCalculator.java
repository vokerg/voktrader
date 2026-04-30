package com.vokerg.voktrader.trade;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PaperFeeCalculator {
    private static final int MONEY_SCALE = 8;

    /**
     * Conservative fake taker fee approximation for binary markets:
     * shares * feeRate * price * (1 - price)
     */
    public BigDecimal estimate(BigDecimal shares, BigDecimal price, BigDecimal feeRate) {
        if (shares == null || price == null || feeRate == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (shares.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0 || feeRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return shares
                .multiply(feeRate)
                .multiply(price)
                .multiply(BigDecimal.ONE.subtract(price))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
