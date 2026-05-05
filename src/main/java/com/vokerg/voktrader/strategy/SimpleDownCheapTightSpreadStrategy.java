package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated
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
    public StrategyDescription description() {
        return new StrategyDescription(
                "Simple Down cheap tight-spread",
                "Deprecated scaffold. Kept available as legacy behavior, not recommended as a template for new strategies.",
                "Buys Down when Down ask is below a configured cheap threshold and spread is tight.",
                "Uses only latest top-of-book Down price and current market. It does not inspect Up, order book depth, fees, open trade limits, cooldowns, or market structure.",
                "Single-condition entry: Down ask <= max ask and Down spread <= max spread. No momentum, no opposite-side confirmation, no depth check.",
                "No dedicated exit logic in this strategy. It depends on external/manual lifecycle behavior and is therefore incomplete as an autonomous strategy.",
                "Very easy to understand and useful as a smoke test for routing a basic buy intent.",
                "Extremely weak as a trading strategy. It can buy a cheap Down for a good reason: the market may already strongly favor Up. "
                        + "It ignores commissions, slippage, order book depth, price age, open trade duplication, trend, reversal context, and exit quality. "
                        + "Because it only cares about Down, it is structurally biased and should not be used for real evaluation.",
                "Do not tune this for production. Keep only as a minimal routing example, or remove once no longer needed."
        );
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
