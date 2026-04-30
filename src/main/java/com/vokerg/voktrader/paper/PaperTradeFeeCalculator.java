package com.vokerg.voktrader.paper;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PaperTradeFeeCalculator {

    public static final int MONEY_SCALE = 8;
    public static final int SHARE_SCALE = 8;

    public EntryFees calculateEntry(
            BigDecimal paperSizeUsd,
            BigDecimal entryPrice,
            BigDecimal feeRate
    ) {
        BigDecimal normalizedFeeRate = feeRate == null ? BigDecimal.ZERO : feeRate;
        BigDecimal grossPaperShares = paperSizeUsd.divide(
                entryPrice,
                SHARE_SCALE,
                RoundingMode.HALF_UP
        );
        BigDecimal entryFeeUsd = calculateFee(grossPaperShares, entryPrice, normalizedFeeRate);
        BigDecimal netPaperShares = grossPaperShares.subtract(
                entryFeeUsd.divide(entryPrice, SHARE_SCALE, RoundingMode.HALF_UP)
        );

        return new EntryFees(
                normalizedFeeRate.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                entryFeeUsd,
                grossPaperShares,
                netPaperShares.setScale(SHARE_SCALE, RoundingMode.HALF_UP)
        );
    }

    public BigDecimal calculateFee(
            BigDecimal shares,
            BigDecimal price,
            BigDecimal feeRate
    ) {
        BigDecimal normalizedFeeRate = feeRate == null ? BigDecimal.ZERO : feeRate;

        return shares
                .multiply(normalizedFeeRate)
                .multiply(price)
                .multiply(BigDecimal.ONE.subtract(price))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateExitPnl(
            BigDecimal netPaperShares,
            BigDecimal exitPrice,
            BigDecimal paperSizeUsd,
            BigDecimal feeRate
    ) {
        BigDecimal exitFeeUsd = calculateFee(netPaperShares, exitPrice, feeRate);

        return netPaperShares
                .multiply(exitPrice)
                .subtract(exitFeeUsd)
                .subtract(paperSizeUsd)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public record EntryFees(
            BigDecimal feeRate,
            BigDecimal entryFeeUsd,
            BigDecimal grossPaperShares,
            BigDecimal netPaperShares
    ) {
    }
}
