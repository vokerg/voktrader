package com.vokerg.voktrader.paper;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PaperTradeFeeCalculator {

    public static final int MONEY_SCALE = 8;
    public static final int SHARE_SCALE = 8;

    public EntryFees calculateEntry(
            BigDecimal fakeSizeUsd,
            BigDecimal entryPrice,
            BigDecimal feeRate
    ) {
        BigDecimal normalizedFeeRate = feeRate == null ? BigDecimal.ZERO : feeRate;
        BigDecimal grossFakeShares = fakeSizeUsd.divide(
                entryPrice,
                SHARE_SCALE,
                RoundingMode.HALF_UP
        );
        BigDecimal entryFeeUsd = fakeSizeUsd
                .multiply(normalizedFeeRate)
                .multiply(entryPrice)
                .multiply(BigDecimal.ONE.subtract(entryPrice))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal netFakeShares = grossFakeShares.subtract(
                entryFeeUsd.divide(entryPrice, SHARE_SCALE, RoundingMode.HALF_UP)
        );

        return new EntryFees(
                normalizedFeeRate.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                entryFeeUsd,
                grossFakeShares,
                netFakeShares.setScale(SHARE_SCALE, RoundingMode.HALF_UP)
        );
    }

    public record EntryFees(
            BigDecimal feeRate,
            BigDecimal entryFeeUsd,
            BigDecimal grossFakeShares,
            BigDecimal netFakeShares
    ) {
    }
}
