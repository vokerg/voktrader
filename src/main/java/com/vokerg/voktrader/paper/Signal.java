package com.vokerg.voktrader.paper;

import java.math.BigDecimal;
import java.time.Instant;

public record Signal(
        String marketId,
        String question,
        String outcome,
        String tokenId,
        SignalType signalType,
        BigDecimal entryPrice,
        BigDecimal paperSizeUsd,
        BigDecimal paperShares,
        BigDecimal feeRate,
        BigDecimal entryFeeUsd,
        BigDecimal grossPaperShares,
        BigDecimal netPaperShares,
        String ruleName,
        String reason,
        Instant createdAt,
        Instant marketEndDate,
        SignalStatus status,
        Instant resolvedAt,
        String winningOutcome,
        BigDecimal paperPnl,
        BigDecimal exitPrice,
        BigDecimal exitValueUsd,
        String exitReason
) {
}
