package com.vokerg.voktrader.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record FeeMetadata(
        String marketId,
        BigDecimal rate,
        int exponent,
        boolean takerOnly,
        String source,
        Instant effectiveAt
) {
    public FeeMetadata {
        Objects.requireNonNull(marketId, "marketId");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (marketId.isBlank()) {
            throw new IllegalArgumentException("marketId must not be blank");
        }
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("fee rate must be non-negative");
        }
        if (exponent < 0) {
            throw new IllegalArgumentException("fee exponent must be non-negative");
        }
    }
}
