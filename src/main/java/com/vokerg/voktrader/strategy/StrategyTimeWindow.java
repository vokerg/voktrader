package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.time.TimeMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StrategyTimeWindow {

    private final MarketSelectionProperties marketSelectionProperties;
    private final TrackedMarketState trackedMarketState;

    public boolean isInsideTradingWindow() {
        var endDate = trackedMarketState.currentEndDate().orElse(null);

        if (endDate == null) {
            return false;
        }

        Duration remaining = Duration.between(TimeMachine.now(), endDate);

        boolean afterMaxRemaining = remaining.compareTo(marketSelectionProperties.maxRemaining()) <= 0;
        boolean beforeMinRemaining = remaining.compareTo(marketSelectionProperties.minRemaining()) >= 0;

        return afterMaxRemaining && beforeMinRemaining;
    }
}
