package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Owns the Strategy V2 choice between the compatibility execution router and
 * the order layer. Strategy code submits typed intent and cannot select either
 * execution path directly.
 */
@Service
public class StrategyIntentBoundary implements EntryAcceptanceService, ExitSubmissionService {
    private final StrategyV2ExecutionProperties executionProperties;
    private final ExecutionRouter executionRouter;
    private final OrderGateway orderGateway;
    private final TradingProperties tradingProperties;

    public StrategyIntentBoundary(
            StrategyV2ExecutionProperties executionProperties,
            ExecutionRouter executionRouter,
            OrderGateway orderGateway,
            TradingProperties tradingProperties
    ) {
        this.executionProperties = executionProperties;
        this.executionRouter = executionRouter;
        this.orderGateway = orderGateway;
        this.tradingProperties = tradingProperties;
    }

    @Override
    public TradeExecutionResult accept(EntryIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return route(intent.tradeIntent(), intent.owner());
    }

    @Override
    public TradeExecutionResult submit(ExitIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return route(intent.tradeIntent(), intent.owner());
    }

    private TradeExecutionResult route(TradeIntent intent, StrategyInstanceKey owner) {
        ExecutionMode mode = configuredMode();
        if (executionProperties.isUseOrderLayer()) {
            OrderGateway gateway = OrderGatewayContext.current().orElse(orderGateway);
            return TradeExecutionResult.fromOrderLifecycle(mode, gateway.submitOrder(intent, owner, mode));
        }
        return executionRouter.route(intent);
    }

    private ExecutionMode configuredMode() {
        ExecutionMode mode = tradingProperties.getMode();
        if (mode == null) {
            throw new IllegalStateException("voktrader.trading.mode must be configured explicitly");
        }
        return mode;
    }
}
