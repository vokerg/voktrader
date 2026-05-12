package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.strategy.StrategyProperties;
import com.vokerg.voktrader.strategy.StrategyRegistry;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.strategy.v2.StrategyV2Engine;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestStrategyResolverTest {
    @Test
    void explicitLegacyStrategyResolvesIndependentOfDefaultActiveStrategy() {
        BacktestStrategyResolver resolver = resolver("strategy-v2");

        TradingStrategy resolved = resolver.resolve("resolution-pressure-fok");

        assertThat(resolved.id()).isEqualTo("resolution-pressure-fok");
    }

    @Test
    void strategyV2TopLevelStrategyResolvesToStrategyV2Engine() {
        BacktestStrategyResolver resolver = resolver("resolution-pressure-fok");

        TradingStrategy resolved = resolver.resolve(StrategyV2Engine.ID);

        assertThat(resolved.id()).isEqualTo(StrategyV2Engine.ID);
    }

    @Test
    void strategyV2InnerIdIsRejectedWithClearMessage() {
        BacktestStrategyResolver resolver = resolver("strategy-v2");

        assertThatThrownBy(() -> resolver.resolve("cfg_v2_liquidity_momentum_paper"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inner YAML strategy id")
                .hasMessageContaining("Use strategyId='strategy-v2'");
    }

    private BacktestStrategyResolver resolver(String activeStrategyId) {
        StrategyRegistry registry = new StrategyRegistry(
                new StrategyProperties(activeStrategyId, null, null, null, null, null, null, null, null),
                List.of(
                        strategy("resolution-pressure-fok"),
                        strategy(StrategyV2Engine.ID)
                )
        );
        StrategyV2Properties strategyV2Properties = new StrategyV2Properties();
        StrategyV2Properties.Strategy inner = new StrategyV2Properties.Strategy();
        inner.setStrategyId("cfg_v2_liquidity_momentum_paper");
        strategyV2Properties.setStrategies(List.of(inner));
        return new BacktestStrategyResolver(registry, strategyV2Properties);
    }

    private TradingStrategy strategy(String id) {
        return new TradingStrategy() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public StrategyDescription description() {
                return new StrategyDescription(id, "test", "test", "test", "test", "test", "test", "test", "test");
            }

            @Override
            public void tick() {
            }
        };
    }
}
