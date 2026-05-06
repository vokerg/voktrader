package com.vokerg.voktrader.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRouter {
    private final TradingProperties properties;
    private final PaperExecutionService paperExecutionService;
    private final LiveShadowExecutionService liveShadowExecutionService;
    private final LiveExecutionService liveExecutionService;

    public TradeExecutionResult route(TradeIntent intent) {
        TradeIntentExecutor override = ExecutionOverrideContext.current();
        if (override != null) {
            return override.execute(intent);
        }

        ExecutionMode mode = properties.getMode() == null ? ExecutionMode.PAPER : properties.getMode();
        log.debug("Routing trade intent: mode={} strategy={} marketId={} tokenId={} outcome={} side={} amountUsd={} limitPrice={} reason={}",
                mode, intent.strategyId(), intent.marketId(), intent.tokenId(), intent.outcome(), intent.side(), intent.amountUsd(), intent.expectedPrice(), intent.reason());
        return switch (mode) {
            case PAPER -> paperExecutionService.execute(intent);
            case LIVE_SHADOW -> liveShadowExecutionService.execute(intent);
            case LIVE_TINY, LIVE -> liveExecutionService.execute(intent, mode);
            case TESTING -> TradeExecutionResult.rejected(mode, null, null, null, null, "TESTING mode requires execution override");
        };
    }
}
