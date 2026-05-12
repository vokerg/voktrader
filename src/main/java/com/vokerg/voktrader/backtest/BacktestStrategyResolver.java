package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.strategy.StrategyRegistry;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.strategy.v2.StrategyV2Engine;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BacktestStrategyResolver {
    private final StrategyRegistry strategyRegistry;
    private final StrategyV2Properties strategyV2Properties;

    public BacktestStrategyResolver(StrategyRegistry strategyRegistry, StrategyV2Properties strategyV2Properties) {
        this.strategyRegistry = strategyRegistry;
        this.strategyV2Properties = strategyV2Properties;
    }

    public TradingStrategy resolve(String strategyId) {
        try {
            return strategyRegistry.strategy(strategyId);
        } catch (IllegalStateException ex) {
            String requested = strategyId == null ? null : strategyId.trim();
            if (requested != null && strategyV2InnerStrategyIds().contains(requested)) {
                throw new IllegalArgumentException(
                        "Strategy id '" + requested + "' is a Strategy V2 inner YAML strategy id, not a top-level strategy id. "
                                + "Use strategyId='" + StrategyV2Engine.ID + "' for /api/backtests and select inner V2 strategies via strategy-v2.engine.active-strategy-ids or strategyYamlOverride.",
                        ex
                );
            }
            throw ex;
        }
    }

    private Set<String> strategyV2InnerStrategyIds() {
        return strategyV2Properties.getStrategies().stream()
                .map(StrategyV2Properties.Strategy::getStrategyId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
    }
}
