package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleDownCheapTightSpreadStrategy implements TradingStrategy {

    public static final String ID = "simple-down-cheap-tight-spread";

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final ExecutionRouter executionRouter;
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

        TradeExecutionResult result = executionRouter.route(TradeIntent.buy(
                market,
                down,
                config.paperSizeUsdOrDefault(),
                ID,
                "down-ask-cheap-spread-tight",
                "Down ask <= " + config.maxAskOrDefault()
                        + " and spread <= " + config.maxSpreadOrDefault()
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
    }
}
