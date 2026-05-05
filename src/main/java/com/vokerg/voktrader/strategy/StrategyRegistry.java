package com.vokerg.voktrader.strategy;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {
    private final StrategyProperties strategyProperties;
    private final Map<String, TradingStrategy> strategiesById;

    public StrategyRegistry(StrategyProperties strategyProperties, List<TradingStrategy> strategies) {
        this.strategyProperties = strategyProperties;
        this.strategiesById = strategies.stream()
                .sorted(Comparator.comparing(TradingStrategy::id))
                .collect(Collectors.toUnmodifiableMap(
                        TradingStrategy::id,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("Duplicate strategy id: " + left.id());
                        }
                ));
    }

    public TradingStrategy activeStrategy() {
        return strategy(strategyProperties.activeOrDefault());
    }

    public TradingStrategy strategy(String strategyId) {
        String requested = strategyId == null || strategyId.isBlank()
                ? strategyProperties.activeOrDefault()
                : strategyId.trim();
        TradingStrategy strategy = strategiesById.get(requested);
        if (strategy == null) {
            throw new IllegalStateException("Unknown strategy id '" + requested + "'. Available strategies: " + strategyIds());
        }
        return strategy;
    }

    public Set<String> strategyIds() {
        return strategiesById.keySet();
    }

    public Map<String, TradingStrategy.StrategyDescription> strategyDescriptions() {
        return strategiesById.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().description(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }
}
