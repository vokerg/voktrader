package com.vokerg.voktrader.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

public record TickSizeMetadata(
        String tokenId,
        String marketId,
        BigDecimal tickSize,
        Instant effectiveAt,
        Instant observedAt,
        TickSizeSource source
) {
}
