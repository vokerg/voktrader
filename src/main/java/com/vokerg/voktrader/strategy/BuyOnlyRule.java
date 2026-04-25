package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.paper.Side;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class BuyOnlyRule {

    private final StrategyConfig strategyConfig;

    public BuyOnlyRule(StrategyConfig strategyConfig) {
        this.strategyConfig = strategyConfig;
    }

    public SignalDecision evaluate(BigDecimal bestAsk, BigDecimal spread) {
        if (bestAsk == null || spread == null) {
            return SignalDecision.noSignal("Missing order book data");
        }

        if (bestAsk.compareTo(strategyConfig.maxYesPrice()) <= 0
                && spread.compareTo(strategyConfig.maxSpread()) <= 0) {
            return SignalDecision.buy(Side.YES, bestAsk, "YES ask is cheap enough and spread is acceptable");
        }

        return SignalDecision.noSignal("Rule conditions not met");
    }
}
