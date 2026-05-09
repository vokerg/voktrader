package com.vokerg.voktrader.strategy.v2;

import java.util.List;

public record StrategyV2Config(
        String schemaVersion,
        StrategyV2Properties.Engine engine,
        List<StrategyV2Properties.Strategy> strategies
) {
}
