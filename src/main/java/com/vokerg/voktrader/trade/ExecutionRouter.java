package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.paper.PaperExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRouter {
    private final TradingProperties properties;
    private final PaperExecutionService paperExecutionService;
    private final LiveOrderGateway liveOrderGateway;

    public TradeExecutionResult route(TradeIntent intent) {
        ExecutionMode mode = properties.getMode();
        if (mode == null) {
            throw new IllegalStateException("voktrader.trading.mode must be configured explicitly");
        }
        if (intent.side() == TradeSide.BUY && !EntryRiskDecisionContext.approves(intent, mode)) {
            return TradeExecutionResult.rejected(
                    mode, null, null, null, null,
                    "BUY rejected because it did not cross EntryAcceptanceService"
            );
        }

        TradeIntentExecutor override = ExecutionOverrideContext.current();
        if (override != null) {
            return override.execute(intent);
        }

        log.debug("Routing trade intent: mode={} strategy={} marketId={} tokenId={} outcome={} side={} amountUsd={} limitPrice={} reason={}",
                mode, intent.strategyId(), intent.marketId(), intent.tokenId(), intent.outcome(), intent.side(), intent.amountUsd(), intent.expectedPrice(), intent.reason());
        return switch (mode) {
            case PAPER -> paperExecutionService.execute(intent);
            case LIVE -> TradeExecutionResult.fromOrderLifecycle(
                    mode,
                    liveOrderGateway.submitOrder(
                            intent,
                            StrategyInstanceKey.of(intent.botId(), intent.strategyId()),
                            mode
                    )
            );
            case BACKTEST -> TradeExecutionResult.rejected(mode, null, null, null, null, "BACKTEST mode requires execution override");
        };
    }
}
