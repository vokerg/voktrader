package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;

public interface OrderGateway extends CancellationSubmissionService {
    OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode);

    default void advanceOpenOrders() {
    }
}
