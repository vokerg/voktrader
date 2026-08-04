package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Candidate-specific portfolio exposure materialized for one entry decision. */
public record PortfolioSnapshot(
        PortfolioKey key,
        Map<PortfolioExposureState, Long> stateCounts,
        long activePositionsInPortfolio,
        long activePositionsInMarket,
        long activePositionsForToken,
        Set<String> activeStrategyIdsInMarket
) {
    public PortfolioSnapshot {
        stateCounts = Map.copyOf(stateCounts);
        activeStrategyIdsInMarket = Set.copyOf(activeStrategyIdsInMarket);
    }

    public static PortfolioSnapshot from(PortfolioKey key, List<TradeEntity> trades) {
        EnumMap<PortfolioExposureState, Long> stateCounts = new EnumMap<>(PortfolioExposureState.class);
        for (PortfolioExposureState state : PortfolioExposureState.values()) {
            stateCounts.put(state, 0L);
        }

        long portfolioCount = 0;
        long marketCount = 0;
        long tokenCount = 0;
        Set<String> strategies = new LinkedHashSet<>();

        for (TradeEntity trade : trades) {
            PortfolioExposureState state = PortfolioExposureState.from(trade.getStatus());
            if (state == null) {
                continue;
            }
            portfolioCount++;
            if (!key.marketId().equals(trade.getMarketId())) {
                continue;
            }
            marketCount++;
            if (trade.getStrategyId() != null) {
                strategies.add(trade.getStrategyId());
            }
            if (key.tokenId().equals(trade.getTokenId())) {
                tokenCount++;
                stateCounts.compute(state, (ignored, count) -> count == null ? 1L : count + 1L);
            }
        }

        return new PortfolioSnapshot(key, stateCounts, portfolioCount, marketCount, tokenCount, strategies);
    }

    public record PortfolioKey(
            Long botId,
            String accountId,
            String marketId,
            String tokenId,
            String outcome,
            ExecutionMode mode
    ) {
    }
}
