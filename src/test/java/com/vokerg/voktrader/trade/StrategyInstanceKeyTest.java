package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class StrategyInstanceKeyTest {
    @Test
    void equalityIncludesBotStrategyConfigAndAccount() {
        StrategyInstanceKey first = StrategyInstanceKey.of(7L, "strategy-v2", "hash-a", "account-1");
        StrategyInstanceKey same = StrategyInstanceKey.of(7L, "strategy-v2", "hash-a", "account-1");
        StrategyInstanceKey differentConfig = StrategyInstanceKey.of(7L, "strategy-v2", "hash-b", "account-1");
        StrategyInstanceKey differentAccount = StrategyInstanceKey.of(7L, "strategy-v2", "hash-a", "account-2");

        assertThat(first).isEqualTo(same);
        assertThat(first.hashCode()).isEqualTo(same.hashCode());
        assertThat(first).isNotEqualTo(differentConfig);
        assertThat(first).isNotEqualTo(differentAccount);
    }

    @Test
    void supportsNullableOptionalScopeFields() {
        StrategyInstanceKey key = StrategyInstanceKey.of(null, "strategy-v2");

        assertThat(key.botId()).isNull();
        assertThat(key.strategyId()).isEqualTo("strategy-v2");
        assertThat(key.configHash()).isNull();
        assertThat(key.accountId()).isNull();
    }

    @Test
    void requiresStrategyId() {
        assertThatNullPointerException()
                .isThrownBy(() -> StrategyInstanceKey.of(1L, null))
                .withMessage("strategyId is required");
    }
}
