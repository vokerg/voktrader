package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import org.springframework.stereotype.Component;

@Component
public class StrategyV2DiagnosticsRecorder {
    private final TradingEventLogger eventLogger;

    public StrategyV2DiagnosticsRecorder(TradingEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    public void rejected(StrategyV2Properties.Strategy strategy, StrategyV2FeatureContext context, String reason) {
        if (strategy.getDiagnostics() != null && !strategy.getDiagnostics().isRecordRejections()) {
            return;
        }
        eventLogger.entryRejected(
                strategy.getStrategyId(),
                strategy.getEntry() == null ? null : strategy.getEntry().getRuleId(),
                context == null ? null : context.market(),
                null,
                reason,
                TelemetryData.data("candidate", context == null ? null : context.candidate().outcome())
        );
    }

    public void routed(StrategyV2Properties.Strategy strategy, StrategyV2FeatureContext context, TradeExecutionResult result) {
        eventLogger.routed(
                "ENTRY_V2",
                strategy.getStrategyId(),
                strategy.getEntry() == null ? null : strategy.getEntry().getRuleId(),
                context.market(),
                null,
                result.message(),
                TelemetryData.data(
                        "candidate", context.candidate().outcome(),
                        "accepted", result.accepted(),
                        "mode", result.mode(),
                        "tradeId", result.tradeId(),
                        "orderId", result.orderId(),
                        "localOrderId", result.localOrderId(),
                        "remoteOrderId", result.remoteOrderId(),
                        "error", result.error()
                )
        );
    }

    public void stateBranch(
            StrategyV2Properties.Strategy strategy,
            StrategyRuntimeState state,
            String marketId,
            String branch,
            String reason
    ) {
        eventLogger.execution(
                "STRATEGY_V2_STATE_BRANCH",
                "STATE_V2",
                strategy.getStrategyId(),
                null,
                state == null || state.strategyInstanceKey() == null ? null : state.strategyInstanceKey().botId(),
                marketId,
                null,
                null,
                reason,
                TelemetryData.data(
                        "strategyInstanceKey", state == null ? null : state.strategyInstanceKey(),
                        "marketId", marketId,
                        "tradeState", state == null || state.currentTradeStatus() == null ? null : state.currentTradeStatus().name(),
                        "entryOrderState", state == null || state.activeEntryOrder() == null || state.activeEntryOrder().status() == null ? null : state.activeEntryOrder().status().name(),
                        "exitOrderState", state == null || state.activeExitOrder() == null || state.activeExitOrder().status() == null ? null : state.activeExitOrder().status().name(),
                        "decisionBranch", branch
                ),
                false
        );
    }
}
