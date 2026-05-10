package com.vokerg.voktrader.api.dashboard.dto;

import java.util.List;

public record DashboardOptionsResponse(
        List<MarketFamilyOption> marketFamilies,
        List<StrategyOption> strategies,
        List<String> botStatuses,
        List<String> tradeStatuses,
        List<String> executionModes,
        List<String> trackingStatuses,
        List<String> resolutionStatuses
) {
    public record MarketFamilyOption(
            String id,
            String asset,
            String interval
    ) {
    }

    public record StrategyOption(
            String id,
            String name,
            String status,
            String intent
    ) {
    }
}
