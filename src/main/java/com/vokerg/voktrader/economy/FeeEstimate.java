package com.vokerg.voktrader.economy;

import java.math.BigDecimal;

public record FeeEstimate(
        LiquidityRole liquidityRole,
        BigDecimal feeRate,
        BigDecimal feeUsd
) {
}
