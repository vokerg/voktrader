package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.paper.FakeSignalService;
import com.vokerg.voktrader.pricing.LatestPriceState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimpleDownCheapTightSpreadStrategy implements TradingStrategy {

    public static final String ID = "simple-down-cheap-tight-spread";

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final FakeSignalService fakeSignalService;
    private final StrategyProperties strategyProperties;
    private final StrategyTimeWindow strategyTimeWindow;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        if (!strategyTimeWindow.isInsideTradingWindow()) {
            return;
        }

        var config = strategyProperties.simpleDownCheapTightSpreadOrDefault();
        var down = latestPriceState.byOutcome("Down").orElse(null);

        if (down == null || down.ask() == null || down.spread() == null) {
            return;
        }

        boolean askIsCheapEnough = down.ask().compareTo(config.maxAskOrDefault()) <= 0;
        boolean spreadIsTightEnough = down.spread().compareTo(config.maxSpreadOrDefault()) <= 0;

        if (!askIsCheapEnough || !spreadIsTightEnough) {
            return;
        }

        var market = trackedMarketState.currentMarket().orElse(null);

        if (market == null) {
            return;
        }

        fakeSignalService.createBuySignal(
                market,
                down,
                config.fakeSizeUsdOrDefault(),
                ID,
                "Down ask <= " + config.maxAskOrDefault()
                        + " and spread <= " + config.maxSpreadOrDefault()
        );
    }
}
