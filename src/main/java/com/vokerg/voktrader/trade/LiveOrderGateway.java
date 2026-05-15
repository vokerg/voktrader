package com.vokerg.voktrader.trade;

import org.springframework.stereotype.Service;

import com.vokerg.voktrader.trade.model.ExecutionMode;

@Service
public class LiveOrderGateway implements OrderGateway {
    private final OrderManager orderManager;

    public LiveOrderGateway(OrderManager orderManager) {
        this.orderManager = orderManager;
    }

    @Override
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        return orderManager.submitOrder(intent, mode);
    }

    @Override
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        return orderManager.cancelOrder(localOrderId, reason);
    }
}
