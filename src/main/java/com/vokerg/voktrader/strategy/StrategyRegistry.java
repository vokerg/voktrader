package com.vokerg.voktrader.strategy;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {

    private final StrategyProperties strategyProperties;
    private final Map<String, TradingStrategy> strategiesById;

    public StrategyRegistry(
            StrategyProperties strategyProperties,
            List<TradingStrategy> strategies
    ) {
        this.strategyProperties = strategyProperties;
        this.strategiesById = strategies.stream()
                .sorted(Comparator.comparing(TradingStrategy::id))
                .collect(Collectors.toUnmodifiableMap(
                        TradingStrategy::id,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException(
                                    "Duplicate strategy id: " + left.id()
                            );
                        }
                ));
    }

    public TradingStrategy activeStrategy() {
        String activeId = strategyProperties.activeOrDefault();
        TradingStrategy strategy = strategiesById.get(activeId);

        if (strategy == null) {
            throw new IllegalStateException(
                    "Unknown strategy id '" + activeId + "'. Available strategies: " + strategyIds()
            );
        }

        return strategy;
    }

    public Set<String> strategyIds() {
        return strategiesById.keySet();
    }
}
