package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class StrategyV2DiagnosticsRecorder {
    private final TradingEventLogger eventLogger;

    public StrategyV2DiagnosticsRecorder(TradingEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    public void rejected(StrategyV2Properties.Strategy strategy, StrategyV2FeatureContext context, String reason) {
        rejected(strategy, context, reason, Map.of());
    }

    public void rejected(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context,
            String reason,
            Map<String, Object> details
    ) {
        if (strategy.getDiagnostics() != null && !strategy.getDiagnostics().isRecordRejections()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidate", context == null || context.candidate() == null ? null : context.candidate().outcome());
        if (details != null) {
            data.putAll(details);
        }
        eventLogger.entryRejected(
                strategy.getStrategyId(),
                strategy.getEntry() == null ? null : strategy.getEntry().getRuleId(),
                context == null ? null : context.market(),
                null,
                reason,
                data
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
                ),
                false
        );
    }

    public void exitDecision(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context,
            StrategyRuntimeState state,
            String decision,
            String reason,
            Map<String, Object> details
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", decision);
        data.put("tradeState", state == null || state.currentTradeStatus() == null ? null : state.currentTradeStatus().name());
        data.put("filledShares", state == null ? null : state.filledShares());
        if (details != null) {
            data.putAll(details);
        }
        eventLogger.execution(
                "STRATEGY_V2_EXIT_DECISION",
                "EXIT_V2",
                strategy.getStrategyId(),
                strategy.getExit() == null ? null : strategy.getExit().getRuleId(),
                state == null || state.strategyInstanceKey() == null ? null : state.strategyInstanceKey().botId(),
                context == null || context.market() == null ? state == null ? null : state.marketId() : context.market().id(),
                context == null || context.candidate() == null ? state == null ? null : state.tokenId() : context.candidate().tokenId(),
                context == null || context.candidate() == null ? null : context.candidate().outcome(),
                reason,
                data,
                false
        );
    }

    public void exitRouted(
            StrategyV2Properties.Strategy strategy,
            StrategyV2Properties.ExitRule rule,
            StrategyV2FeatureContext context,
            TradeExecutionResult result
    ) {
        eventLogger.routed(
                "EXIT_V2",
                strategy.getStrategyId(),
                rule == null || rule.getName() == null ? strategy.getExit().getRuleId() : rule.getName(),
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
                ),
                result.accepted()
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
