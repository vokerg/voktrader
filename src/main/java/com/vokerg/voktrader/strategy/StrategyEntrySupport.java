package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.TradeIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class StrategyEntrySupport {
    private static final BigDecimal MIN_MID_SUM = new BigDecimal("0.97");
    private static final BigDecimal MAX_MID_SUM = new BigDecimal("1.03");
    private static final BigDecimal TWO = new BigDecimal("2");

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final StrategyTimeWindow strategyTimeWindow;
    private final StrategyTradeSupport tradeSupport;
    private final ExecutionRouter executionRouter;
    private final Clock clock;

    @Autowired
    public StrategyEntrySupport(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            StrategyTradeSupport tradeSupport,
            ExecutionRouter executionRouter
    ) {
        this(latestPriceState, trackedMarketState, strategyTimeWindow, tradeSupport, executionRouter, Clock.systemUTC());
    }

    StrategyEntrySupport(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            StrategyTradeSupport tradeSupport,
            ExecutionRouter executionRouter,
            Clock clock
    ) {
        this.latestPriceState = latestPriceState;
        this.trackedMarketState = trackedMarketState;
        this.strategyTimeWindow = strategyTimeWindow;
        this.tradeSupport = tradeSupport;
        this.executionRouter = executionRouter;
        this.clock = clock;
    }

    public void evaluateUpDownEntry(
            String strategyId,
            String ruleId,
            EntryRules rules,
            PriceRecorder priceRecorder,
            EntryEvaluator evaluator
    ) {
        if (!strategyTimeWindow.isInsideTradingWindow()) {
            return;
        }

        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        if (market == null || market.id() == null) {
            return;
        }

        OutcomePrice up = latestPriceState.byOutcome("Up").orElse(null);
        OutcomePrice down = latestPriceState.byOutcome("Down").orElse(null);
        if (!StrategyExitSupport.hasCompletePrice(up) || !StrategyExitSupport.hasCompletePrice(down)) {
            return;
        }

        priceRecorder.record(up);
        priceRecorder.record(down);

        Instant now = clock.instant();
        if (isStale(up, now, rules) || isStale(down, now, rules) || midSumOutsideOne(up, down)) {
            return;
        }

        evaluator.evaluate(new EntryContext(market, up, down, mid(up), mid(down), now))
                .filter(signal -> entryAllowed(strategyId, market, signal.candidate(), rules, now))
                .ifPresent(signal -> routeBuy(strategyId, ruleId, market, signal));
    }

    public static BigDecimal mid(OutcomePrice price) {
        return price.bid()
                .add(price.ask())
                .divide(TWO, 8, RoundingMode.HALF_UP);
    }

    private boolean isStale(OutcomePrice price, Instant now, EntryRules rules) {
        return Duration.between(price.updatedAt(), now).toMillis() > rules.maxDataAgeMs();
    }

    private boolean midSumOutsideOne(OutcomePrice up, OutcomePrice down) {
        BigDecimal midSum = mid(up).add(mid(down));
        return midSum.compareTo(MIN_MID_SUM) < 0 || midSum.compareTo(MAX_MID_SUM) > 0;
    }

    private boolean entryAllowed(
            String strategyId,
            GammaMarketDto market,
            OutcomePrice candidate,
            EntryRules rules,
            Instant now
    ) {
        Long botId = tradeSupport.currentBotId();
        return !tradeSupport.hasOpenTrade(strategyId, botId, market.id())
                && !tradeSupport.marketTradeLimitReached(strategyId, botId, market.id(), rules.maxTradesPerMarket())
                && !tradeSupport.closedTradeCooldownActive(strategyId, botId, market.id(), rules.closedTradeCooldownSeconds(), now)
                && !tradeSupport.sameOutcomeLossLockoutActive(strategyId, botId, market.id(), candidate.tokenId());
    }

    private void routeBuy(String strategyId, String ruleId, GammaMarketDto market, EntrySignal signal) {
        var result = executionRouter.route(TradeIntent.buy(
                market,
                signal.candidate(),
                signal.paperSizeUsd(),
                strategyId,
                ruleId,
                signal.reason()
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
    }

    @FunctionalInterface
    public interface EntryEvaluator {
        Optional<EntrySignal> evaluate(EntryContext context);
    }

    @FunctionalInterface
    public interface PriceRecorder {
        void record(OutcomePrice price);
    }

    public record EntryRules(
            long maxDataAgeMs,
            long closedTradeCooldownSeconds,
            int maxTradesPerMarket
    ) {
    }

    public record EntryContext(
            GammaMarketDto market,
            OutcomePrice up,
            OutcomePrice down,
            BigDecimal upMid,
            BigDecimal downMid,
            Instant now
    ) {
    }

    public record EntrySignal(
            OutcomePrice candidate,
            BigDecimal paperSizeUsd,
            String reason
    ) {
    }
}
