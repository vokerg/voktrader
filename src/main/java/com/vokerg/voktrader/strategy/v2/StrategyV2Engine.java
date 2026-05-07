package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketDataProvider;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.TradingStrategy;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.TradingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
public class StrategyV2Engine implements TradingStrategy {
    public static final String ID = "strategy-v2";

    private final StrategyV2Properties properties;
    private final StrategyV2Registry registry;
    private final StrategyMarketDataProvider marketDataProvider;
    private final TrackedMarketState trackedMarketState;
    private final StrategyV2FeatureResolver featureResolver;
    private final StrategyV2EntryEvaluator entryEvaluator;
    private final StrategyV2ExitEvaluator exitEvaluator;
    private final TradingProperties tradingProperties;

    public StrategyV2Engine(
            StrategyV2Properties properties,
            StrategyV2Registry registry,
            StrategyMarketDataProvider marketDataProvider,
            TrackedMarketState trackedMarketState,
            StrategyV2FeatureResolver featureResolver,
            StrategyV2EntryEvaluator entryEvaluator,
            StrategyV2ExitEvaluator exitEvaluator,
            TradingProperties tradingProperties
    ) {
        this.properties = properties;
        this.registry = registry;
        this.marketDataProvider = marketDataProvider;
        this.trackedMarketState = trackedMarketState;
        this.featureResolver = featureResolver;
        this.entryEvaluator = entryEvaluator;
        this.exitEvaluator = exitEvaluator;
        this.tradingProperties = tradingProperties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyDescription description() {
        return new StrategyDescription(
                "Strategy V2 config engine",
                properties.getEngine().isEnabled() ? "enabled" : "disabled",
                "Interprets YAML-backed Strategy V2 configs instead of hardcoded strategy classes.",
                "Uses StrategyMarketView, full book summaries, rolling price samples, and configured feature namespace.",
                "Selects a candidate, evaluates a small condition tree, and routes configured BUY entry actions.",
                "Exit schema is validated; execution remains delegated to existing hardcoded exits until V2 exit execution is enabled.",
                "Good for parameter sweeps and safer config-only experiments.",
                "Maker/live behavior is deliberately blocked by existing lifecycle guardrails when reconciliation is absent.",
                "Put configured strategy ids under strategy-v2.engine.active-strategy-ids or leave empty to run all enabled V2 strategies."
        );
    }

    @Override
    public void tick() {
        if (!properties.getEngine().isEnabled()) {
            return;
        }
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        StrategyMarketView marketView = marketDataProvider.currentUpDownMarket().orElse(null);
        if (market == null || marketView == null) {
            return;
        }
        if (properties.getEngine().isRequireMidSumSane() && !midSumSane(marketView)) {
            return;
        }
        ExecutionMode mode = tradingProperties.getMode() == null ? ExecutionMode.PAPER : tradingProperties.getMode();
        for (StrategyV2Properties.Strategy strategy : registry.activeStrategies()) {
            if (!modeAllowed(strategy, mode)) {
                continue;
            }
            exitEvaluator.evaluate(strategy);
            BigDecimal orderUsd = strategy.getEntry().getAction().getSize().getPaperUsd();
            List<StrategyV2FeatureContext> contexts = featureResolver.contexts(market, marketView, orderUsd);
            boolean accepted = entryEvaluator.evaluate(strategy, contexts, featureResolver, mode)
                    .map(result -> result.accepted() && "single_market_single_position".equals(properties.getEngine().getDecisionMode()))
                    .orElse(false);
            if (accepted) {
                break;
            }
        }
    }

    private boolean modeAllowed(StrategyV2Properties.Strategy strategy, ExecutionMode mode) {
        List<String> allowed = strategy.getAllowedExecutionModes();
        return allowed == null || allowed.isEmpty() || allowed.stream().anyMatch(value -> value.equalsIgnoreCase(mode.name()));
    }

    private boolean midSumSane(StrategyMarketView marketView) {
        BigDecimal up = marketView.outcome("Up").map(com.vokerg.voktrader.strategy.StrategyOutcomeView::mid).orElse(null);
        BigDecimal down = marketView.outcome("Down").map(com.vokerg.voktrader.strategy.StrategyOutcomeView::mid).orElse(null);
        if (up == null || down == null) {
            return !properties.getEngine().isRequireCompleteUpDownPrice();
        }
        BigDecimal sum = up.add(down);
        return sum.compareTo(properties.getEngine().getMinMidSum()) >= 0
                && sum.compareTo(properties.getEngine().getMaxMidSum()) <= 0;
    }

}
