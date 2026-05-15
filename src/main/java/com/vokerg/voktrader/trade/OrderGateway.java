package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;

public interface OrderGateway {
    OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode);

    OrderLifecycleResult cancelOrder(String localOrderId, String reason);

    default void advanceOpenOrders() {
    }
}
