package com.vokerg.voktrader.trade;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;

@Primary
@Service
public class RoutingTradeStateProvider implements TradeStateProvider {
    private final DbTradeStateProvider dbTradeStateProvider;
    private final PaperOrderGateway paperOrderGateway;
    private final TradingProperties tradingProperties;
    private final StrategyV2ExecutionProperties executionProperties;

    public RoutingTradeStateProvider(
            DbTradeStateProvider dbTradeStateProvider,
            PaperOrderGateway paperOrderGateway,
            TradingProperties tradingProperties,
            StrategyV2ExecutionProperties executionProperties
    ) {
        this.dbTradeStateProvider = dbTradeStateProvider;
        this.paperOrderGateway = paperOrderGateway;
        this.tradingProperties = tradingProperties;
        this.executionProperties = executionProperties;
    }

    @Override
    public StrategyRuntimeState getState(StrategyInstanceKey strategyInstanceKey, String marketId) {
        return BacktestTradeStateContext.current()
                .map(provider -> provider.getState(strategyInstanceKey, marketId))
                .orElseGet(() -> currentProvider().getState(strategyInstanceKey, marketId));
    }

    private TradeStateProvider currentProvider() {
        if (executionProperties.isUseOrderLayer() && tradingProperties.getMode() == ExecutionMode.PAPER) {
            return paperOrderGateway;
        }
        return dbTradeStateProvider;
    }
}
