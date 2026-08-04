package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.springframework.stereotype.Service;

@Service
public class LiveOrderGateway implements OrderGateway {
    private final OrderManager orderManager;

    public LiveOrderGateway(OrderManager orderManager) {
        this.orderManager = orderManager;
    }

    @Override
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        if (intent.side() == TradeSide.BUY && !EntryRiskDecisionContext.approves(intent, mode)) {
            String message = "BUY rejected: approved central entry risk decision is required";
            return new OrderLifecycleResult(false, null, null, null, null, null, null, message, message);
        }
        return orderManager.submitOrder(intent, mode);
    }

    @Override
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        return orderManager.cancelOrder(localOrderId, reason);
    }
}
