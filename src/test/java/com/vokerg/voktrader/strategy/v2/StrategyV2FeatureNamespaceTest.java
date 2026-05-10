package com.vokerg.voktrader.strategy.v2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyV2FeatureNamespaceTest {
    @Test
    void includesRuntimeAliasesAndRejectsUntrackedExcursions() {
        assertThat(StrategyV2FeatureNamespace.isKnown("trade.estimated_net_pnl_usd")).isTrue();
        assertThat(StrategyV2FeatureNamespace.isKnown("trade.hold_seconds")).isTrue();
        assertThat(StrategyV2FeatureNamespace.isKnown("position.unrealized_pnl_usd")).isTrue();
        assertThat(StrategyV2FeatureNamespace.isKnown("trade.max_adverse_excursion_usd")).isFalse();
        assertThat(StrategyV2FeatureNamespace.isKnown("candidate.taker_sell.net_proceeds_usd")).isFalse();
    }

    @Test
    void includesGeneratedCandidateAndOppositeFeatures() {
        assertThat(StrategyV2FeatureNamespace.isKnown("candidate.depth_imbalance_0_02")).isTrue();
        assertThat(StrategyV2FeatureNamespace.isKnown("opposite.token_id")).isTrue();
        assertThat(StrategyV2FeatureNamespace.isKnown("opposite.taker_buy.total_cost_usd")).isTrue();
    }
}
