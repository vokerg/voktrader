package com.vokerg.voktrader.trade;

@FunctionalInterface
public interface TradeIntentExecutor {
    TradeExecutionResult execute(TradeIntent intent);
}
