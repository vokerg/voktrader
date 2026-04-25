package com.vokerg.voktrader.performance;

import java.math.BigDecimal;

public record TradeStats(
        BigDecimal winRate,
        BigDecimal averagePnl,
        BigDecimal totalPnl
) {
}
