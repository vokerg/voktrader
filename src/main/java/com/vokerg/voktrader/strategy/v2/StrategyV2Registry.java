package com.vokerg.voktrader.strategy.v2;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StrategyV2Registry {
    private final StrategyV2Properties properties;

    public StrategyV2Registry(StrategyV2Properties properties) {
        this.properties = properties;
    }

    public List<StrategyV2Properties.Strategy> activeStrategies() {
        Map<String, StrategyV2Properties.Strategy> byId = strategiesById();
        List<String> active = properties.getEngine().getActiveStrategyIds();
        if (active == null || active.isEmpty()) {
            return byId.values().stream().filter(StrategyV2Properties.Strategy::isEnabled).toList();
        }
        return active.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .filter(StrategyV2Properties.Strategy::isEnabled)
                .toList();
    }

    public Map<String, StrategyV2Properties.Strategy> strategiesById() {
        Map<String, StrategyV2Properties.Strategy> result = new LinkedHashMap<>();
        for (StrategyV2Properties.Strategy strategy : properties.getStrategies()) {
            result.put(strategy.getStrategyId(), strategy);
        }
        return result;
    }
}
