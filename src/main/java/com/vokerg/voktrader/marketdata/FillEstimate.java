package com.vokerg.voktrader.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

public record FillEstimate(
        String tokenId,
        String outcome,
        OrderBookSide side,
        BigDecimal requestedUsd,
        BigDecimal requestedShares,
        BigDecimal filledShares,
        BigDecimal notionalUsd,
        BigDecimal averagePrice,
        BigDecimal worstPrice,
        boolean complete,
        int levelsConsumed,
        Instant bookUpdatedAt
) {
    public BigDecimal unfilledShares() {
        if (requestedShares == null || filledShares == null) {
            return null;
        }
        BigDecimal remaining = requestedShares.subtract(filledShares);
        return remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining;
    }

    public BigDecimal unspentUsd() {
        if (requestedUsd == null || notionalUsd == null) {
            return null;
        }
        BigDecimal remaining = requestedUsd.subtract(notionalUsd);
        return remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining;
    }
}
