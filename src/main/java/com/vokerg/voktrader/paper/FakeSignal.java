package com.vokerg.voktrader.paper;

import java.math.BigDecimal;
import java.time.Instant;

public record FakeSignal(
        String marketId,
        String question,
        String outcome,
        String tokenId,
        BigDecimal entryPrice,
        BigDecimal fakeSizeUsd,
        BigDecimal fakeShares,
        String ruleName,
        String reason,
        Instant createdAt
) {
}