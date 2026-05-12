package com.vokerg.voktrader.api.strategy;

import com.vokerg.voktrader.strategy.StrategyProperties;
import com.vokerg.voktrader.strategy.StrategyRegistry;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.strategy.v2.StrategyV2SetCatalog;
import com.vokerg.voktrader.strategy.v2.StrategyV2Engine;
import com.vokerg.voktrader.strategy.v2.StrategyV2Properties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/strategies")
public class StrategyCatalogController {
    private final StrategyRegistry strategyRegistry;
    private final StrategyProperties strategyProperties;
    private final StrategyV2Properties strategyV2Properties;
    private final StrategyV2SetCatalog strategyV2SetCatalog;

    public StrategyCatalogController(
            StrategyRegistry strategyRegistry,
            StrategyProperties strategyProperties,
            StrategyV2Properties strategyV2Properties,
            StrategyV2SetCatalog strategyV2SetCatalog
    ) {
        this.strategyRegistry = strategyRegistry;
        this.strategyProperties = strategyProperties;
        this.strategyV2Properties = strategyV2Properties;
        this.strategyV2SetCatalog = strategyV2SetCatalog;
    }

    @GetMapping
    public StrategyCatalogResponse catalog() {
        Map<String, TradingStrategy.StrategyDescription> descriptions = strategyRegistry.strategyDescriptions();
        List<TopLevelStrategy> topLevel = descriptions.entrySet().stream()
                .map(entry -> new TopLevelStrategy(entry.getKey(), entry.getValue()))
                .toList();
        List<StrategyV2InnerStrategy> v2Strategies = strategyV2Properties.getStrategies().stream()
                .map(strategy -> new StrategyV2InnerStrategy(
                        strategy.getStrategyId(),
                        strategy.isEnabled(),
                        strategy.getProfile(),
                        strategy.getDescription(),
                        strategy.getAllowedExecutionModes()
                ))
                .toList();
        return new StrategyCatalogResponse(
                topLevel,
                strategyProperties.activeOrDefault(),
                strategyV2Properties.getEngine().getActiveStrategyIds(),
                v2Strategies,
                strategyV2SetCatalog.setIds().stream().sorted().toList(),
                strategyRegistry.strategyIds().stream().sorted().toList(),
                StrategyV2Engine.ID,
                "Only top-level registered strategy IDs are valid for /api/backtests.strategyId. Use strategy-v2 for Strategy V2; inner YAML IDs are selected by strategy-v2.engine.active-strategy-ids or strategyYamlOverride."
        );
    }

    public record StrategyCatalogResponse(
            List<TopLevelStrategy> topLevelStrategies,
            String currentDefaultActiveStrategy,
            List<String> strategyV2ActiveInnerStrategyIds,
            List<StrategyV2InnerStrategy> strategyV2ConfiguredStrategies,
            List<String> strategyV2SetIds,
            List<String> validBacktestStrategyIds,
            String strategyV2TopLevelBacktestId,
            String backtestStrategyIdRule
    ) {
    }

    public record TopLevelStrategy(
            String id,
            TradingStrategy.StrategyDescription description
    ) {
    }

    public record StrategyV2InnerStrategy(
            String id,
            boolean enabled,
            String profile,
            String description,
            List<String> allowedExecutionModes
    ) {
    }
}

