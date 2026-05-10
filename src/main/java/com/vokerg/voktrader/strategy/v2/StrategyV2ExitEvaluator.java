package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class StrategyV2ExitEvaluator {
    private final StrategyV2FeatureResolver featureResolver;
    private final StrategyV2ConditionEvaluator conditionEvaluator;
    private final StrategyV2OrderActionBuilder orderActionBuilder;
    private final StrategyV2DiagnosticsRecorder diagnosticsRecorder;

    public StrategyV2ExitEvaluator(
            StrategyV2FeatureResolver featureResolver,
            StrategyV2ConditionEvaluator conditionEvaluator,
            StrategyV2OrderActionBuilder orderActionBuilder,
            StrategyV2DiagnosticsRecorder diagnosticsRecorder
    ) {
        this.featureResolver = featureResolver;
        this.conditionEvaluator = conditionEvaluator;
        this.orderActionBuilder = orderActionBuilder;
        this.diagnosticsRecorder = diagnosticsRecorder;
    }

    public void evaluate(StrategyV2Properties.Strategy strategy) {
        evaluate(strategy, null);
    }

    public void evaluate(StrategyV2Properties.Strategy strategy, StrategyRuntimeState runtimeState) {
        diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "NO_POSITION", "exit requires current market and runtime position", Map.of());
    }

    public Optional<TradeExecutionResult> evaluate(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyMarketView marketView,
            StrategyRuntimeState runtimeState,
            ExecutionMode mode
    ) {
        if (strategy.getExit() == null || !strategy.getExit().isEnabled()) {
            diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "EXIT_DISABLED", "exit disabled", Map.of());
            return Optional.empty();
        }
        if (runtimeState == null || !runtimeState.hasPosition() || positive(runtimeState.filledShares()).isEmpty()) {
            diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "NO_POSITION", "no position available for exit", Map.of());
            return Optional.empty();
        }
        TradeStatus status = runtimeState.currentTradeStatus();
        if (status != null && status.isPendingEntry()) {
            diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "ENTRY_PENDING", "entry pending; normal exit suppressed", Map.of());
            return Optional.empty();
        }
        if (status != null && status.isPendingExit()) {
            diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "EXIT_ALREADY_PENDING", "exit already pending", Map.of());
            return Optional.empty();
        }
        if (status == TradeStatus.PARTIALLY_OPEN && !strategy.getPartialFillManagement().isAllowExitPartialPosition()) {
            diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "EXIT_DISABLED", "partial-position exit disabled", Map.of());
            return Optional.empty();
        }

        Optional<StrategyV2FeatureContext> context = positionContext(strategy, market, marketView, runtimeState);
        if (context.isEmpty()) {
            diagnosticsRecorder.exitDecision(strategy, null, runtimeState, "NO_POSITION", "position token missing from current market view", Map.of());
            return Optional.empty();
        }

        for (StrategyV2Properties.ExitRule rule : strategy.getExit().getRules()) {
            if (!conditionEvaluator.matches(context.get(), rule.getWhen(), featureResolver)) {
                continue;
            }
            diagnosticsRecorder.exitDecision(
                    strategy,
                    context.get(),
                    runtimeState,
                    "RULE_MATCHED",
                    "exit rule matched",
                    details("rule", rule.getName(), "action", rule.getAction())
            );
            if (!supportedSellAction(rule.getAction())) {
                diagnosticsRecorder.exitDecision(
                        strategy,
                        context.get(),
                        runtimeState,
                        "UNSUPPORTED_ACTION",
                        "unsupported exit action",
                        details("rule", rule.getName(), "action", rule.getAction())
                );
                return Optional.empty();
            }
            TradeExecutionResult result = orderActionBuilder.routeExit(strategy, rule, context.get(), mode);
            diagnosticsRecorder.exitRouted(strategy, rule, context.get(), result);
            diagnosticsRecorder.exitDecision(
                    strategy,
                    context.get(),
                    runtimeState,
                    result.accepted() ? "EXIT_ORDER_ROUTED" : "EXIT_ORDER_REJECTED",
                    result.accepted() ? "exit order routed" : "exit order rejected",
                    details("rule", rule.getName(), "message", result.message(), "error", result.error())
            );
            return Optional.of(result);
        }

        diagnosticsRecorder.exitDecision(strategy, context.get(), runtimeState, "NO_RULE_MATCHED", "no exit rule matched", Map.of());
        return Optional.empty();
    }

    private Optional<StrategyV2FeatureContext> positionContext(
            StrategyV2Properties.Strategy strategy,
            GammaMarketDto market,
            StrategyMarketView marketView,
            StrategyRuntimeState runtimeState
    ) {
        BigDecimal orderUsd = strategy.getEntry() == null || strategy.getEntry().getAction() == null || strategy.getEntry().getAction().getSize() == null
                ? BigDecimal.ONE
                : strategy.getEntry().getAction().getSize().getPaperUsd();
        List<StrategyV2FeatureContext> contexts = featureResolver.contexts(market, marketView, orderUsd, runtimeState);
        return contexts.stream()
                .filter(context -> context.candidate() != null && context.candidate().tokenId().equals(runtimeState.tokenId()))
                .findFirst();
    }

    private Optional<BigDecimal> positive(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? Optional.empty() : Optional.of(value);
    }

    private boolean supportedSellAction(String action) {
        return action == null || "SELL".equalsIgnoreCase(action) || "SELL_NOW".equalsIgnoreCase(action);
    }

    private Map<String, Object> details(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
