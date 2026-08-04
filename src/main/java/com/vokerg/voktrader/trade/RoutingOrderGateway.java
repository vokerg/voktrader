package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.paper.PaperOrderGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RoutingOrderGateway implements OrderGateway {
    private final TradingProperties tradingProperties;
    private final StrategyV2ExecutionProperties executionProperties;
    private final LiveOrderGateway liveOrderGateway;
    private final PaperOrderGateway paperOrderGateway;

    public RoutingOrderGateway(
            TradingProperties tradingProperties,
            StrategyV2ExecutionProperties executionProperties,
            LiveOrderGateway liveOrderGateway,
            PaperOrderGateway paperOrderGateway
    ) {
        this.tradingProperties = tradingProperties;
        this.executionProperties = executionProperties;
        this.liveOrderGateway = liveOrderGateway;
        this.paperOrderGateway = paperOrderGateway;
    }

    @Override
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        if (intent.side() == TradeSide.BUY && !EntryRiskDecisionContext.approves(intent, mode)) {
            String message = "BUY rejected: approved central entry risk decision is required";
            return new OrderLifecycleResult(false, null, null, null, null, null, null, message, message);
        }
        return gateway(mode).submitOrder(intent, owner, mode);
    }

    @Override
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        return gateway(tradingProperties.getMode()).cancelOrder(localOrderId, reason);
    }

    @Override
    public void advanceOpenOrders() {
        if (!executionProperties.isUseOrderLayer()) {
            return;
        }
        gateway(tradingProperties.getMode()).advanceOpenOrders();
    }

    private OrderGateway gateway(ExecutionMode mode) {
        if (mode == ExecutionMode.PAPER) {
            return paperOrderGateway;
        }
        return liveOrderGateway;
    }
}
