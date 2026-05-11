package com.vokerg.voktrader.strategy.v2;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class StrategyV2Validator {
    private static final Set<String> ORDER_TYPES = Set.of("FOK", "FAK", "GTC", "GTD");
    private static final Set<String> OPERATORS = Set.of(
            "<", "<=", ">", ">=", "==", "!=", "between", "outside",
            "contains", "matches_regex", "in", "not_in", "exists", "missing"
    );
    private final StrategyV2Properties properties;
    private final StrategyV2SimulationConfig simulationConfig;

    public StrategyV2Validator(StrategyV2Properties properties, StrategyV2SimulationConfig simulationConfig) {
        this.properties = properties;
        this.simulationConfig = simulationConfig;
    }

    @PostConstruct
    public void validateAtStartup() {
        validate(properties);
    }

    public void validate(StrategyV2Properties candidate) {
        if (!candidate.getEngine().isEnabled()) {
            return;
        }
        if (!"2.0".equals(candidate.getSchemaVersion())) {
            throw new IllegalStateException("Strategy V2 schema_version must be 2.0");
        }
        Set<String> ids = new HashSet<>();
        for (StrategyV2Properties.Strategy strategy : candidate.getStrategies()) {
            require(strategy.getStrategyId() != null && !strategy.getStrategyId().isBlank(), "Strategy V2 strategy_id is required");
            require(ids.add(strategy.getStrategyId()), "Duplicate Strategy V2 strategy_id: " + strategy.getStrategyId());
            validateOrderType(strategy.getEntry().getAction().getOrderType());
            validateCondition(strategy.getEntry().getWhen());
            validateExit(strategy.getExit());
            require(simulationConfig.supportedFillModel(strategy.getSimulation().getFillModel()),
                    "Unsupported Strategy V2 simulation fill_model for " + strategy.getStrategyId() + ": " + strategy.getSimulation().getFillModel());
            if (strategy.getEntry().getAction().getPostOnly() != null && strategy.getEntry().getAction().getPostOnly()
                    && Set.of("FOK", "FAK").contains(strategy.getEntry().getAction().getOrderType().toUpperCase())) {
                throw new IllegalStateException("Strategy V2 post_only is invalid for FOK/FAK: " + strategy.getStrategyId());
            }
        }
        for (String active : candidate.getEngine().getActiveStrategyIds()) {
            require(ids.contains(active), "Unknown Strategy V2 active_strategy_ids entry: " + active);
        }
    }

    private void validateExit(StrategyV2Properties.Exit exit) {
        if (exit == null || exit.getRules() == null) {
            return;
        }
        for (StrategyV2Properties.ExitRule rule : exit.getRules()) {
            if (rule.getOrderType() != null) {
                validateOrderType(rule.getOrderType());
            }
            validateCondition(rule.getWhen());
        }
    }

    private void validateOrderType(String orderType) {
        require(orderType == null || ORDER_TYPES.contains(orderType.toUpperCase()), "Unsupported Strategy V2 order_type: " + orderType);
    }

    private void validateCondition(StrategyV2Properties.Condition condition) {
        if (condition == null) {
            return;
        }
        validateChildren(condition.getAll());
        validateChildren(condition.getAny());
        validateCondition(condition.getNot());
        if (condition.getFeature() != null) {
            require(StrategyV2FeatureNamespace.isKnown(condition.getFeature()), "Unknown Strategy V2 feature: " + condition.getFeature());
            require(condition.getOp() == null || OPERATORS.contains(condition.getOp()), "Unsupported Strategy V2 operator: " + condition.getOp());
        }
    }

    private void validateChildren(List<StrategyV2Properties.Condition> children) {
        if (children != null) {
            children.forEach(this::validateCondition);
        }
    }

    private void require(boolean expression, String message) {
        if (!expression) {
            throw new IllegalStateException(message);
        }
    }
}
