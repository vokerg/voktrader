package com.vokerg.voktrader.trade;

public interface OrderGateway {
    OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode);

    OrderLifecycleResult cancelOrder(String localOrderId, String reason);

    default void advanceOpenOrders() {
    }
}
