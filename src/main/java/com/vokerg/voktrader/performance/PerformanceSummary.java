package com.vokerg.voktrader.performance;

import java.math.BigDecimal;

public record PerformanceSummary(
        long totalSignals,
        long won,
        long lost,
        BigDecimal fakePnl
) {
}
