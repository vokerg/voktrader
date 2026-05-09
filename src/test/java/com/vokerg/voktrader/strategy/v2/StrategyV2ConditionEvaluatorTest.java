package com.vokerg.voktrader.strategy.v2;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyV2ConditionEvaluatorTest {
    private final StrategyV2ConditionEvaluator evaluator = new StrategyV2ConditionEvaluator();
    private final StrategyV2FeatureResolver resolver = new StrategyV2FeatureResolver();

    @Test
    void evaluatesNestedAllAnyAndNotConditions() {
        HashMap<String, Object> features = new HashMap<>();
        features.put("candidate.spread", new BigDecimal("0.02"));
        features.put("candidate.taker_buy.fillable", true);
        features.put("market.seconds_to_expiry", 30L);
        StrategyV2FeatureContext context = new StrategyV2FeatureContext(null, null, null, null, Instant.now(), features);

        StrategyV2Properties.Condition root = new StrategyV2Properties.Condition();
        StrategyV2Properties.Condition spread = leaf("candidate.spread", "<=", "0.03");
        StrategyV2Properties.Condition any = new StrategyV2Properties.Condition();
        any.setAny(List.of(leaf("candidate.taker_buy.fillable", "==", true), leaf("candidate.spread", "<", "0.01")));
        StrategyV2Properties.Condition not = new StrategyV2Properties.Condition();
        not.setNot(leaf("market.seconds_to_expiry", "<", 5));
        root.setAll(List.of(spread, any, not));

        assertThat(evaluator.matches(context, root, resolver)).isTrue();
    }

    @Test
    void explainsFirstFailedLeafCondition() {
        HashMap<String, Object> features = new HashMap<>();
        features.put("candidate.spread", new BigDecimal("0.09"));
        features.put("candidate.bid", new BigDecimal("0.50"));
        StrategyV2FeatureContext context = new StrategyV2FeatureContext(null, null, null, null, Instant.now(), features);

        StrategyV2Properties.Condition root = new StrategyV2Properties.Condition();
        root.setAll(List.of(
                leaf("candidate.spread", "<=", "0.08"),
                leaf("candidate.bid", ">=", "0.38")
        ));

        StrategyV2ConditionEvaluator.ConditionFailure failure = evaluator.firstFailure(context, root, resolver).orElseThrow();

        assertThat(failure.feature()).isEqualTo("candidate.spread");
        assertThat(failure.op()).isEqualTo("<=");
        assertThat(failure.actual()).isEqualTo(new BigDecimal("0.09"));
        assertThat(failure.expected()).isEqualTo("0.08");
        assertThat(failure.reason()).isEqualTo("entry condition failed: candidate.spread <= 0.08");
    }

    private StrategyV2Properties.Condition leaf(String feature, String op, Object value) {
        StrategyV2Properties.Condition condition = new StrategyV2Properties.Condition();
        condition.setFeature(feature);
        condition.setOp(op);
        condition.setValue(value);
        return condition;
    }
}
