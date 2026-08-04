package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioSnapshotTest {
    @Test
    void snapshotSeparatesPendingProvisionalAndExitingExposure() {
        PortfolioSnapshot.PortfolioKey key = new PortfolioSnapshot.PortfolioKey(
                7L, "0xaccount", "market-a", "token-up", "Up", ExecutionMode.LIVE);

        PortfolioSnapshot snapshot = PortfolioSnapshot.from(key, List.of(
                trade("strategy-a", "market-a", "token-up", TradeStatus.ENTRY_PENDING),
                trade("strategy-b", "market-a", "token-up", TradeStatus.OPEN),
                trade("strategy-c", "market-a", "token-up", TradeStatus.PARTIALLY_CLOSED),
                trade("strategy-d", "market-b", "other-token", TradeStatus.OPEN),
                trade("strategy-e", "market-a", "token-up", TradeStatus.CLOSED)
        ));

        assertThat(snapshot.activePositionsInPortfolio()).isEqualTo(4);
        assertThat(snapshot.activePositionsInMarket()).isEqualTo(3);
        assertThat(snapshot.activePositionsForToken()).isEqualTo(3);
        assertThat(snapshot.activeStrategyIdsInMarket())
                .containsExactlyInAnyOrder("strategy-a", "strategy-b", "strategy-c");
        assertThat(snapshot.stateCounts())
                .containsEntry(PortfolioExposureState.PENDING_ENTRY, 1L)
                .containsEntry(PortfolioExposureState.PROVISIONAL_POSITION, 1L)
                .containsEntry(PortfolioExposureState.EXITING_POSITION, 1L);
    }

    private TradeEntity trade(String strategyId, String marketId, String tokenId, TradeStatus status) {
        TradeEntity trade = mock(TradeEntity.class);
        when(trade.getStrategyId()).thenReturn(strategyId);
        when(trade.getMarketId()).thenReturn(marketId);
        when(trade.getTokenId()).thenReturn(tokenId);
        when(trade.getStatus()).thenReturn(status);
        return trade;
    }
}
