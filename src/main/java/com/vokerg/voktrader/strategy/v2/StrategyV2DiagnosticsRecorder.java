package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
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
}
