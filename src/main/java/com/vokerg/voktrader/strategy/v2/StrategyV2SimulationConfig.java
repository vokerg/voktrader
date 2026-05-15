package com.vokerg.voktrader.strategy.v2;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class StrategyV2SimulationConfig {
    private static final Set<String> FILL_MODELS = Set.of(
            "taker_instant",
            "taker_book_walk",
            "maker_touch",
            "maker_queue_simple",
            "maker_queue_pessimistic",
            "maker_never",
            "hybrid"
    );

    public boolean supportedFillModel(String fillModel) {
        return fillModel == null || FILL_MODELS.contains(fillModel);
    }
}
