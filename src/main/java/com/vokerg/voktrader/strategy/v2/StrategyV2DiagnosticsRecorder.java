package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeEventEntity;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StrategyV2DiagnosticsRecorder {
    private final TradingEventLogger eventLogger;
    private final StrategyV2DiagnosticsProperties properties;
    private final TradeEventRepository tradeEventRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, Instant> entryPulseAt = new ConcurrentHashMap<>();

    public StrategyV2DiagnosticsRecorder(
            TradingEventLogger eventLogger,
            StrategyV2DiagnosticsProperties properties,
            TradeEventRepository tradeEventRepository,
            ObjectMapper objectMapper
    ) {
        this.eventLogger = eventLogger;
        this.properties = properties;
        this.tradeEventRepository = tradeEventRepository;
        this.objectMapper = objectMapper;
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
        entryPulse(strategy, context, reason, data);
        eventLogger.entryRejected(
                strategy.getStrategyId(),
                strategy.getEntry() == null ? null : strategy.getEntry().getRuleId(),
                context == null ? null : context.market(),
                null,
                reason,
                data
        );
    }

    private void entryPulse(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context,
            String reason,
            Map<String, Object> data
    ) {
        if (!properties.isEntryPulseEnabled() || strategy == null) {
            return;
        }
        long intervalSeconds = Math.max(1, properties.getEntryPulseSeconds());
        Instant now = Instant.now();
        String failedFeature = String.valueOf(data.getOrDefault("failedFeature", "candidate"));
        String key = BotRuntimeContextHolder.currentBotId().orElse(null)
                + "|" + strategy.getStrategyId()
                + "|" + (context == null || context.market() == null ? null : context.market().id())
                + "|" + failedFeature;
        Instant previous = entryPulseAt.get(key);
        if (previous != null && Duration.between(previous, now).getSeconds() < intervalSeconds) {
            return;
        }
        entryPulseAt.put(key, now);

        eventLogger.execution(
                "STRATEGY_V2_ENTRY_PULSE",
                "ENTRY_V2",
                strategy.getStrategyId(),
                strategy.getEntry() == null ? null : strategy.getEntry().getRuleId(),
                BotRuntimeContextHolder.currentBotId().orElse(null),
                context == null || context.market() == null ? null : context.market().id(),
                context == null || context.candidate() == null ? null : context.candidate().tokenId(),
                context == null || context.candidate() == null ? null : context.candidate().outcome(),
                reason,
                data,
                true
        );
    }

    public void routed(StrategyV2Properties.Strategy strategy, StrategyV2FeatureContext context, TradeExecutionResult result) {
        if (result != null && result.tradeId() != null) {
            persistDecision(
                    result.tradeId(),
                    result.orderId(),
                    "STRATEGY_V2_ENTRY_DECISION",
                    "entry rule routed",
                    entryPayload(strategy, context, result)
            );
        }
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
        if (result != null && result.tradeId() != null) {
            persistDecision(
                    result.tradeId(),
                    result.orderId(),
                    "STRATEGY_V2_EXIT_DECISION",
                    "exit rule routed",
                    exitPayload(strategy, rule, context, result)
            );
        }
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

    private Map<String, Object> entryPayload(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context,
            TradeExecutionResult result
    ) {
        Map<String, Object> payload = basePayload(strategy, context, result, "ENTRY");
        payload.put("entryRuleId", strategy.getEntry() == null ? null : strategy.getEntry().getRuleId());
        payload.put("entryAction", strategy.getEntry() == null ? null : strategy.getEntry().getAction());
        payload.put("candidateSelection", strategy.getCandidateSelection());
        payload.put("matchedConditions", conditionValues(strategy.getEntry() == null ? null : strategy.getEntry().getWhen(), context));
        payload.put("reasonCategory", "entry");
        return payload;
    }

    private Map<String, Object> exitPayload(
            StrategyV2Properties.Strategy strategy,
            StrategyV2Properties.ExitRule rule,
            StrategyV2FeatureContext context,
            TradeExecutionResult result
    ) {
        Map<String, Object> payload = basePayload(strategy, context, result, "EXIT");
        payload.put("exitRuleId", strategy.getExit() == null ? null : strategy.getExit().getRuleId());
        payload.put("exitRuleName", rule == null ? null : rule.getName());
        payload.put("exitAction", rule == null ? null : rule.getAction());
        payload.put("exitOrderType", rule == null ? null : rule.getOrderType());
        payload.put("exitLiquidityRole", rule == null ? null : rule.getLiquidityRole());
        payload.put("matchedConditions", conditionValues(rule == null ? null : rule.getWhen(), context));
        payload.put("reasonCategory", classifyExit(rule));
        payload.put("estimatedGrossPnlUsd", feature(context, "position.unrealized_gross_pnl_usd"));
        payload.put("estimatedNetPnlUsd", firstFeature(context, "position.unrealized_pnl_usd", "trade.estimated_net_pnl_usd"));
        payload.put("estimatedNetPnlPct", firstFeature(context, "position.unrealized_pnl_pct", "trade.estimated_net_pnl_pct"));
        payload.put("realizedFeeUsdAtDecision", firstFeature(context, "position.realized_fee_usd", "trade.realized_fee_usd"));
        payload.put("exitFeeEstimateUsd", firstFeature(context, "candidate.taker_sell.fee_usd", "candidate.maker_sell.fee_usd"));
        payload.put("feeOrSlippageNegative", feeOrSlippageNegative(context));
        return payload;
    }

    private Map<String, Object> basePayload(
            StrategyV2Properties.Strategy strategy,
            StrategyV2FeatureContext context,
            TradeExecutionResult result,
            String phase
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("strategyId", strategy == null ? null : strategy.getStrategyId());
        payload.put("mode", result == null ? null : result.mode());
        payload.put("accepted", result == null ? null : result.accepted());
        payload.put("tradeId", result == null ? null : result.tradeId());
        payload.put("orderId", result == null ? null : result.orderId());
        payload.put("localOrderId", result == null ? null : result.localOrderId());
        payload.put("remoteOrderId", result == null ? null : result.remoteOrderId());
        payload.put("tradeStatus", result == null || result.tradeStatus() == null ? null : result.tradeStatus().name());
        payload.put("orderStatus", result == null || result.orderStatus() == null ? null : result.orderStatus().name());
        payload.put("message", result == null ? null : result.message());
        payload.put("error", result == null ? null : result.error());
        payload.put("candidate", context == null || context.candidate() == null ? null : context.candidate().outcome());
        payload.put("tokenId", context == null || context.candidate() == null ? null : context.candidate().tokenId());
        payload.put("marketId", context == null || context.market() == null ? null : context.market().id());
        payload.put("marketSlug", context == null || context.market() == null ? null : context.market().slug());
        payload.put("features", context == null ? Map.of() : context.features());
        payload.put("diagnosticSchema", "strategy-v2-decision-v1");
        return payload;
    }

    private List<Map<String, Object>> conditionValues(StrategyV2Properties.Condition condition, StrategyV2FeatureContext context) {
        if (condition == null) {
            return List.of();
        }
        List<Map<String, Object>> values = new java.util.ArrayList<>();
        collectConditionValues(condition, context, values, false);
        return values;
    }

    private void collectConditionValues(
            StrategyV2Properties.Condition condition,
            StrategyV2FeatureContext context,
            List<Map<String, Object>> values,
            boolean negated
    ) {
        if (condition == null) {
            return;
        }
        if (condition.getAll() != null) {
            condition.getAll().forEach(child -> collectConditionValues(child, context, values, negated));
            return;
        }
        if (condition.getAny() != null) {
            condition.getAny().forEach(child -> collectConditionValues(child, context, values, negated));
            return;
        }
        if (condition.getNot() != null) {
            collectConditionValues(condition.getNot(), context, values, !negated);
            return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("feature", condition.getFeature());
        value.put("op", condition.getOp() == null ? "exists" : condition.getOp());
        value.put("expected", condition.getValues() == null ? condition.getValue() : condition.getValues());
        value.put("actual", feature(context, condition.getFeature()));
        value.put("negated", negated);
        values.add(value);
    }

    private Object feature(StrategyV2FeatureContext context, String feature) {
        if (context == null || feature == null) {
            return null;
        }
        return context.features().get(feature);
    }

    private Object firstFeature(StrategyV2FeatureContext context, String... features) {
        for (String feature : features) {
            Object value = feature(context, feature);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String classifyExit(StrategyV2Properties.ExitRule rule) {
        String text = ((rule == null ? "" : String.valueOf(rule.getName())) + " " + (rule == null ? "" : String.valueOf(rule.getAction()))).toLowerCase();
        if (text.contains("manual")) return "manual";
        if (text.contains("stop") || text.contains("loss")) return "stop";
        if (text.contains("pressure") || text.contains("flip")) return "pressure_flip";
        if (text.contains("flush") || text.contains("final")) return "final_flush";
        if (text.contains("profit") || text.contains("take")) return "profit";
        if (text.contains("reconcil")) return "reconciliation";
        return "rule";
    }

    private boolean feeOrSlippageNegative(StrategyV2FeatureContext context) {
        BigDecimal gross = decimal(feature(context, "position.unrealized_gross_pnl_usd"));
        BigDecimal net = decimal(firstFeature(context, "position.unrealized_pnl_usd", "trade.estimated_net_pnl_usd"));
        return gross != null && net != null && gross.compareTo(BigDecimal.ZERO) >= 0 && net.compareTo(BigDecimal.ZERO) < 0;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void persistDecision(Long tradeId, Long orderId, String eventType, String message, Map<String, Object> payload) {
        try {
            tradeEventRepository.save(TradeEventEntity.of(tradeId, orderId, null, eventType, message, objectMapper.writeValueAsString(payload)));
        } catch (RuntimeException e) {
            Map<String, Object> failureDetails = new LinkedHashMap<>();
            failureDetails.put("eventType", eventType);
            failureDetails.put("tradeId", tradeId);
            failureDetails.put("orderId", orderId);
            eventLogger.execution(
                    "STRATEGY_V2_DIAGNOSTIC_PERSIST_FAILED",
                    "DIAGNOSTICS",
                    String.valueOf(payload.get("strategyId")),
                    null,
                    null,
                    String.valueOf(payload.get("marketId")),
                    String.valueOf(payload.get("tokenId")),
                    String.valueOf(payload.get("candidate")),
                    e.getMessage(),
                    failureDetails,
                    true
            );
        }
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
