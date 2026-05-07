package com.vokerg.voktrader.strategy.v2;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyV2CandidateSelectorTest {
    private final StrategyV2CandidateSelector selector = new StrategyV2CandidateSelector();

    @Test
    void scoreModeChoosesHighestScoreAboveMinimum() {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        StrategyV2Properties.CandidateSelection selection = new StrategyV2Properties.CandidateSelection();
        selection.setType("score");
        selection.setCandidates(List.of("Up", "Down"));
        selection.setMinScore(new BigDecimal("0.01"));
        StrategyV2Properties.ScoreExpression up = new StrategyV2Properties.ScoreExpression();
        up.setExpression("up.mid_move_5s + up.depth_imbalance_0_03 - up.spread");
        StrategyV2Properties.ScoreExpression down = new StrategyV2Properties.ScoreExpression();
        down.setExpression("down.mid_move_5s + down.depth_imbalance_0_03 - down.spread");
        selection.setScore(java.util.Map.of("Up", up, "Down", down));
        strategy.setCandidateSelection(selection);

        StrategyV2FeatureContext upContext = context("Up", java.util.Map.of(
                "up.mid_move_5s", new BigDecimal("0.02"),
                "up.depth_imbalance_0_03", new BigDecimal("0.20"),
                "up.spread", new BigDecimal("0.02")
        ));
        StrategyV2FeatureContext downContext = context("Down", java.util.Map.of(
                "down.mid_move_5s", new BigDecimal("0.01"),
                "down.depth_imbalance_0_03", new BigDecimal("0.02"),
                "down.spread", new BigDecimal("0.02")
        ));

        assertThat(selector.select(strategy, List.of(upContext, downContext))).contains(upContext);
    }

    private StrategyV2FeatureContext context(String outcome, java.util.Map<String, Object> features) {
        com.vokerg.voktrader.strategy.StrategyOutcomeView view = mock(com.vokerg.voktrader.strategy.StrategyOutcomeView.class);
        when(view.outcome()).thenReturn(outcome);
        HashMap<String, Object> mutable = new HashMap<>(features);
        return new StrategyV2FeatureContext(null, null, view, null, Instant.now(), mutable);
    }
}
