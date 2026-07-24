package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.paper.PaperExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.vokerg.voktrader.trade.model.ExecutionMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRouter {
    private final TradingProperties properties;
    private final PaperExecutionService paperExecutionService;
    private final LiveExecutionService liveExecutionService;

    public TradeExecutionResult route(TradeIntent intent) {
        TradeIntentExecutor override = ExecutionOverrideContext.current();
        if (override != null) {
            return override.execute(intent);
        }

        ExecutionMode mode = properties.getMode();
        if (mode == null) {
            throw new IllegalStateException("voktrader.trading.mode must be configured explicitly");
        }
        log.debug("Routing trade intent: mode={} strategy={} marketId={} tokenId={} outcome={} side={} amountUsd={} limitPrice={} reason={}",
                mode, intent.strategyId(), intent.marketId(), intent.tokenId(), intent.outcome(), intent.side(), intent.amountUsd(), intent.expectedPrice(), intent.reason());
        return switch (mode) {
            case PAPER -> paperExecutionService.execute(intent);
            case LIVE -> liveExecutionService.execute(intent, mode);
            case BACKTEST -> TradeExecutionResult.rejected(mode, null, null, null, null, "BACKTEST mode requires execution override");
        };
    }
}
