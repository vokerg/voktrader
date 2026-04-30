package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "voktrader.strategy.simple-signal-logger",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class SimpleSignalLogger {
    private static final BigDecimal MAX_ASK = new BigDecimal("0.25");
    private static final BigDecimal MAX_SPREAD = new BigDecimal("0.03");
    private static final BigDecimal ORDER_SIZE_USD = new BigDecimal("1.00");
    private static final String STRATEGY_ID = "simple-down-cheap-tight-spread";
    private static final String RULE_ID = "down-ask-cheap-spread-tight";

    private final LatestPriceState latestPriceState;
    private final MarketSelectionProperties marketSelectionProperties;
    private final TrackedMarketState trackedMarketState;
    private final ExecutionRouter executionRouter;

    private boolean alreadySubmittedDownIntent = false;

    @Scheduled(fixedRate = 1000)
    public void checkForSignal() {
        if (alreadySubmittedDownIntent) {
            return;
        }
        if (!isInsideTradingWindow()) {
            return;
        }

        var down = latestPriceState.byOutcome("Down").orElse(null);
        if (down == null || down.ask() == null || down.spread() == null) {
            return;
        }

        boolean askIsCheapEnough = down.ask().compareTo(MAX_ASK) <= 0;
        boolean spreadIsTightEnough = down.spread().compareTo(MAX_SPREAD) <= 0;
        if (!askIsCheapEnough || !spreadIsTightEnough) {
            return;
        }

        var market = trackedMarketState.currentMarket().orElse(null);
        if (market == null) {
            return;
        }

        TradeIntent intent = TradeIntent.buy(
                market,
                down,
                ORDER_SIZE_USD,
                STRATEGY_ID,
                RULE_ID,
                "Down ask <= " + MAX_ASK + " and spread <= " + MAX_SPREAD
        );

        TradeExecutionResult result = executionRouter.route(intent);
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());

        if (result.accepted()) {
            alreadySubmittedDownIntent = true;
        }
    }

    private boolean isInsideTradingWindow() {
        var endDate = trackedMarketState.currentEndDate().orElse(null);
        if (endDate == null) {
            return false;
        }

        Duration remaining = Duration.between(Instant.now(), endDate);
        boolean afterMaxRemaining = remaining.compareTo(marketSelectionProperties.maxRemaining()) <= 0;
        boolean beforeMinRemaining = remaining.compareTo(marketSelectionProperties.minRemaining()) >= 0;
        return afterMaxRemaining && beforeMinRemaining;
    }
}
