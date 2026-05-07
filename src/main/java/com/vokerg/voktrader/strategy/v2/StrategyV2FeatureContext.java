package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record StrategyV2FeatureContext(
        GammaMarketDto market,
        StrategyMarketView marketView,
        StrategyOutcomeView candidate,
        StrategyOutcomeView opposite,
        Instant now,
        Map<String, Object> features
) {
    public Optional<Object> feature(String name) {
        return Optional.ofNullable(features.get(name));
    }

    public BigDecimal decimal(String name) {
        Object value = features.get(name);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return null;
    }
}
