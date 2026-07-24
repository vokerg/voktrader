package com.vokerg.voktrader.trade;

import org.springframework.stereotype.Service;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;

@Service
public class LiveOrderGateway implements OrderGateway {
    private final OrderManager orderManager;
    private final LiveArmService liveArmService;

    public LiveOrderGateway(OrderManager orderManager, LiveArmService liveArmService) {
        this.orderManager = orderManager;
        this.liveArmService = liveArmService;
    }

    @Override
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        if (mode == ExecutionMode.LIVE && intent.side() == TradeSide.BUY) {
            LiveArmService.LiveArmStatus armStatus = liveArmService.status();
            if (!armStatus.entryAllowed()) {
                String message = "LIVE entry rejected before executor call: " + armStatus.entryBlockReason();
                return new OrderLifecycleResult(
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        message,
                        message
                );
            }
        }
        return orderManager.submitOrder(intent, mode);
    }

    @Override
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        return orderManager.cancelOrder(localOrderId, reason);
    }
}
