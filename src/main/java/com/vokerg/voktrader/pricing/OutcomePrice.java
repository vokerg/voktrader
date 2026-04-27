package com.vokerg.voktrader.pricing;

import java.math.BigDecimal;
import java.time.Instant;

public record OutcomePrice(
        String tokenId,
        String outcome,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal spread,
        Instant updatedAt
) {
}