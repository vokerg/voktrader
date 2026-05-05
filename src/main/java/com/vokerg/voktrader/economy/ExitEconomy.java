package com.vokerg.voktrader.economy;

import java.math.BigDecimal;

public record ExitEconomy(
        LiquidityRole liquidityRole,
        BigDecimal grossExitValueUsd,
        BigDecimal estimatedExitFeeUsd,
        BigDecimal estimatedTotalFeeUsd,
        BigDecimal estimatedNetPnlUsd,
        BigDecimal minimumProfitUsd,
        boolean minimumProfitReached
) {
}
