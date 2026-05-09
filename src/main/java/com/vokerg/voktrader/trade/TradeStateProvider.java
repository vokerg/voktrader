package com.vokerg.voktrader.trade;

public interface TradeStateProvider {
    StrategyRuntimeState getState(StrategyInstanceKey strategyInstanceKey, String marketId);
}
