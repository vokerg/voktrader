package com.vokerg.voktrader.trade;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RoutingTradeStateProvider implements TradeStateProvider {
    private final DbTradeStateProvider dbTradeStateProvider;

    public RoutingTradeStateProvider(DbTradeStateProvider dbTradeStateProvider) {
        this.dbTradeStateProvider = dbTradeStateProvider;
    }

    @Override
    public StrategyRuntimeState getState(StrategyInstanceKey strategyInstanceKey, String marketId) {
        return BacktestTradeStateContext.current()
                .map(provider -> provider.getState(strategyInstanceKey, marketId))
                .orElseGet(() -> dbTradeStateProvider.getState(strategyInstanceKey, marketId));
    }
}
