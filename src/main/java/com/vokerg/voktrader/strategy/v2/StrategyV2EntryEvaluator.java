package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@Component
public class StrategyV2EntryEvaluator {
    private final StrategyV2CandidateSelector candidateSelector;
    private final StrategyV2ConditionEvaluator conditionEvaluator;
    private final StrategyV2OrderActionBuilder orderActionBuilder;
    private final StrategyV2DiagnosticsRecorder diagnosticsRecorder;

    public StrategyV2EntryEvaluator(
            StrategyV2CandidateSelector candidateSelector,
            StrategyV2ConditionEvaluator conditionEvaluator,
            StrategyV2OrderActionBuilder orderActionBuilder,
            StrategyV2DiagnosticsRecorder diagnosticsRecorder
    ) {
        this.candidateSelector = candidateSelector;
        this.conditionEvaluator = conditionEvaluator;
        this.orderActionBuilder = orderActionBuilder;
        this.diagnosticsRecorder = diagnosticsRecorder;
    }

    public Optional<TradeExecutionResult> evaluate(
            StrategyV2Properties.Strategy strategy,
            List<StrategyV2FeatureContext> contexts,
            StrategyV2FeatureResolver featureResolver,
            ExecutionMode mode
    ) {
        if (strategy.getEntry() == null || !strategy.getEntry().isEnabled()) {
            return Optional.empty();
        }
        Optional<StrategyV2FeatureContext> selected = candidateSelector.select(strategy, contexts);
        if (selected.isEmpty()) {
            diagnosticsRecorder.rejected(strategy, null, "no candidate selected");
            return Optional.empty();
        }
        StrategyV2FeatureContext context = selected.get();
        if (!conditionEvaluator.matches(context, strategy.getEntry().getWhen(), featureResolver)) {
            StrategyV2ConditionEvaluator.ConditionFailure failure = conditionEvaluator
                    .firstFailure(context, strategy.getEntry().getWhen(), featureResolver)
                    .orElse(null);
            if (failure == null) {
                diagnosticsRecorder.rejected(strategy, context, "entry conditions not met");
            } else {
                diagnosticsRecorder.rejected(
                        strategy,
                        context,
                        failure.reason(),
                        failureDetails(failure)
                );
            }
            return Optional.empty();
        }
        TradeExecutionResult result = orderActionBuilder.routeEntry(strategy, context, mode);
        diagnosticsRecorder.routed(strategy, context, result);
        return Optional.of(result);
    }

    private Map<String, Object> failureDetails(StrategyV2ConditionEvaluator.ConditionFailure failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("failedFeature", failure.feature());
        details.put("failedOperator", failure.op());
        details.put("actual", failure.actual());
        details.put("expected", failure.expected());
        return details;
    }
}
