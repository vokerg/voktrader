package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.EntryIntent;
import com.vokerg.voktrader.trade.LegacyStrategyIntentAdapter;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
    private final LegacyStrategyIntentAdapter intentAdapter;
    private final TradingEventLogger eventLogger;
    private final StrategyMarketDataProvider marketDataProvider;
    private final Clock clock;

    @Autowired
    public StrategyEntrySupport(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            StrategyTradeSupport tradeSupport,
            LegacyStrategyIntentAdapter intentAdapter,
            TradingEventLogger eventLogger,
            StrategyMarketDataProvider marketDataProvider
    ) {
        this(latestPriceState, trackedMarketState, strategyTimeWindow, tradeSupport, intentAdapter, eventLogger, marketDataProvider, Clock.systemUTC());
    }

    StrategyEntrySupport(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            StrategyTradeSupport tradeSupport,
            Object legacyRouter,
            TradingEventLogger eventLogger,
            StrategyMarketDataProvider marketDataProvider,
            Clock clock
    ) {
        this(
                latestPriceState,
                trackedMarketState,
                strategyTimeWindow,
                tradeSupport,
                LegacyStrategyIntentAdapter.fromLegacyRouter(legacyRouter),
                eventLogger,
                marketDataProvider,
                clock
        );
    }

    private StrategyEntrySupport(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            StrategyTradeSupport tradeSupport,
            LegacyStrategyIntentAdapter intentAdapter,
            TradingEventLogger eventLogger,
            StrategyMarketDataProvider marketDataProvider,
            Clock clock
    ) {
        this.latestPriceState = latestPriceState;
        this.trackedMarketState = trackedMarketState;
        this.strategyTimeWindow = strategyTimeWindow;
        this.tradeSupport = tradeSupport;
        this.intentAdapter = intentAdapter;
        this.eventLogger = eventLogger;
        this.marketDataProvider = marketDataProvider;
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
            eventLogger.entryRejected(strategyId, ruleId, null, null, "outside trading window", Map.of());
            return;
        }

        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);
        if (market == null || market.id() == null) {
            eventLogger.entryRejected(strategyId, ruleId, market, null, "no current market", Map.of());
            return;
        }

        OutcomePrice up = latestPriceState.byOutcome("Up").orElse(null);
        OutcomePrice down = latestPriceState.byOutcome("Down").orElse(null);
        if (!StrategyExitSupport.hasCompletePrice(up) || !StrategyExitSupport.hasCompletePrice(down)) {
            eventLogger.entryRejected(
                    strategyId,
                    ruleId,
                    market,
                    null,
                    "incomplete up/down price state",
                    Map.of("hasUp", StrategyExitSupport.hasCompletePrice(up), "hasDown", StrategyExitSupport.hasCompletePrice(down))
            );
            return;
        }

        priceRecorder.record(up);
        priceRecorder.record(down);

        Instant now = TimeMachine.now(clock);
        if (isStale(up, now, rules) || isStale(down, now, rules)) {
            eventLogger.entryRejected(
                    strategyId,
                    ruleId,
                    market,
                    null,
                    "stale price state",
                    Map.of(
                            "upAgeMs", Duration.between(up.updatedAt(), now).toMillis(),
                            "downAgeMs", Duration.between(down.updatedAt(), now).toMillis(),
                            "maxAgeMs", rules.maxDataAgeMs()
                    )
            );
            return;
        }

        if (midSumOutsideOne(up, down)) {
            eventLogger.entryRejected(
                    strategyId,
                    ruleId,
                    market,
                    null,
                    "mid sum outside sane range",
                    Map.of("upMid", mid(up), "downMid", mid(down), "midSum", mid(up).add(mid(down)))
            );
            return;
        }

        Optional<StrategyMarketView> providedMarketView = marketDataProvider == null
                ? Optional.empty()
                : Optional.ofNullable(marketDataProvider.currentUpDownMarket()).flatMap(optional -> optional);
        StrategyMarketView marketView = providedMarketView
                .orElseGet(() -> new LegacyStrategyMarketView(market, up, down, now));
        EntryContext context = new EntryContext(market, up, down, mid(up), mid(down), now, marketView);
        evaluator.evaluate(context)
                .ifPresentOrElse(signal -> {
                    if (!entryAllowed(strategyId, ruleId, market, signal.candidate(), rules, now)) {
                        return;
                    }
                    eventLogger.entrySignal(
                            strategyId,
                            ruleId,
                            market,
                            signal.candidate(),
                            signal.reason(),
                            Map.of("orderSizeUsd", signal.orderSizeUsd())
                    );
                    routeBuy(strategyId, ruleId, market, signal);
                }, () -> eventLogger.entryRejected(
                        strategyId,
                        ruleId,
                        market,
                        null,
                        "strategy produced no entry signal",
                        Map.of("upMid", context.upMid(), "downMid", context.downMid())
                ));
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
            String ruleId,
            GammaMarketDto market,
            OutcomePrice candidate,
            EntryRules rules,
            Instant now
    ) {
        Long botId = tradeSupport.currentBotId();
        if (tradeSupport.hasOpenTrade(strategyId, botId, market.id())) {
            eventLogger.entryRejected(strategyId, ruleId, market, candidate, "open trade already exists", TelemetryData.data("botId", botId));
            return false;
        }
        if (tradeSupport.marketTradeLimitReached(strategyId, botId, market.id(), rules.maxTradesPerMarket())) {
            eventLogger.entryRejected(
                    strategyId,
                    ruleId,
                    market,
                    candidate,
                    "market trade limit reached",
                    TelemetryData.data("botId", botId, "maxTradesPerMarket", rules.maxTradesPerMarket())
            );
            return false;
        }
        if (tradeSupport.closedTradeCooldownActive(strategyId, botId, market.id(), rules.closedTradeCooldownSeconds(), now)) {
            eventLogger.entryRejected(
                    strategyId,
                    ruleId,
                    market,
                    candidate,
                    "closed trade cooldown active",
                    TelemetryData.data("botId", botId, "cooldownSeconds", rules.closedTradeCooldownSeconds())
            );
            return false;
        }
        if (tradeSupport.sameOutcomeLossLockoutActive(strategyId, botId, market.id(), candidate.tokenId())) {
            eventLogger.entryRejected(strategyId, ruleId, market, candidate, "same outcome loss lockout active", TelemetryData.data("botId", botId));
            return false;
        }
        return true;
    }

    private void routeBuy(String strategyId, String ruleId, GammaMarketDto market, EntrySignal signal) {
        var result = intentAdapter.routeEntry(EntryIntent.buy(
                tradeSupport.currentBotId(),
                market,
                signal.candidate(),
                signal.orderSizeUsd(),
                signal.orderType(),
                signal.postOnly(),
                signal.limitPrice() == null ? signal.candidate().ask() : signal.limitPrice(),
                strategyId,
                ruleId,
                signal.reason()
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
        eventLogger.routed(
                "ENTRY",
                strategyId,
                ruleId,
                market,
                signal.candidate(),
                signal.reason(),
                TelemetryData.data(
                        "accepted", result.accepted(),
                        "mode", result.mode(),
                        "tradeId", result.tradeId(),
                        "orderId", result.orderId(),
                        "tradeStatus", result.tradeStatus(),
                        "orderStatus", result.orderStatus(),
                        "message", result.message()
                )
        );
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
            Instant now,
            StrategyMarketView marketView
    ) {
        public StrategyOutcomeView upOutcome() {
            return marketView.up();
        }

        public StrategyOutcomeView downOutcome() {
            return marketView.down();
        }
    }

    public record EntrySignal(
            OutcomePrice candidate,
            BigDecimal orderSizeUsd,
            TradeOrderType orderType,
            boolean postOnly,
            BigDecimal limitPrice,
            String reason
    ) {
        public EntrySignal(OutcomePrice candidate, BigDecimal orderSizeUsd, String reason) {
            this(candidate, orderSizeUsd, TradeOrderType.FOK, false, candidate == null ? null : candidate.ask(), reason);
        }

        public EntrySignal(OutcomePrice candidate, BigDecimal orderSizeUsd, TradeOrderType orderType, BigDecimal limitPrice, String reason) {
            this(candidate, orderSizeUsd, orderType, orderType.prefersMaker(), limitPrice, reason);
        }

        public static EntrySignal makerBuy(OutcomePrice candidate, BigDecimal orderSizeUsd, String reason) {
            return new EntrySignal(candidate, orderSizeUsd, TradeOrderType.GTC, true, candidate == null ? null : candidate.bid(), reason);
        }
    }

    private record LegacyStrategyMarketView(
            GammaMarketDto market,
            OutcomePrice upPrice,
            OutcomePrice downPrice,
            Instant now
    ) implements StrategyMarketView {
        @Override
        public StrategyOutcomeView up() {
            return new LegacyStrategyOutcomeView(upPrice, now);
        }

        @Override
        public StrategyOutcomeView down() {
            return new LegacyStrategyOutcomeView(downPrice, now);
        }

        @Override
        public java.util.List<StrategyOutcomeView> outcomes() {
            return java.util.List.of(up(), down());
        }

        @Override
        public Optional<StrategyOutcomeView> outcome(String outcome) {
            return outcomes().stream()
                    .filter(view -> view.outcome().equalsIgnoreCase(outcome))
                    .findFirst();
        }

        @Override
        public Optional<StrategyOutcomeView> token(String tokenId) {
            return outcomes().stream()
                    .filter(view -> view.tokenId().equals(tokenId))
                    .findFirst();
        }

        @Override
        public Optional<Long> secondsToExpiry() {
            if (market.endDate() == null) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(now, market.endDate()).toSeconds());
        }
    }

    private record LegacyStrategyOutcomeView(OutcomePrice price, Instant now) implements StrategyOutcomeView {
        @Override public String outcome() { return price.outcome(); }
        @Override public String tokenId() { return price.tokenId(); }
        @Override public Optional<com.vokerg.voktrader.marketdata.OutcomeOrderBook> orderBook() { return Optional.empty(); }
        @Override public BigDecimal mid() { return StrategyEntrySupport.mid(price); }
        @Override public BigDecimal spread() { return price.spread(); }
        @Override public Optional<Long> priceAgeMs() { return Optional.ofNullable(price.updatedAt()).map(updatedAt -> Duration.between(updatedAt, now).toMillis()); }
        @Override public Optional<Long> bookAgeMs() { return Optional.empty(); }
        @Override public BigDecimal bidDepth() { return BigDecimal.ZERO; }
        @Override public BigDecimal askDepth() { return BigDecimal.ZERO; }
        @Override public BigDecimal bidDepthWithin(BigDecimal priceRange) { return BigDecimal.ZERO; }
        @Override public BigDecimal askDepthWithin(BigDecimal priceRange) { return BigDecimal.ZERO; }
        @Override public Optional<com.vokerg.voktrader.marketdata.OrderBookLevel> bestBidLevel() { return Optional.empty(); }
        @Override public Optional<com.vokerg.voktrader.marketdata.OrderBookLevel> bestAskLevel() { return Optional.empty(); }
        @Override public Optional<com.vokerg.voktrader.marketdata.FillEstimate> estimateTakerBuy(BigDecimal amountUsd) { return Optional.empty(); }
        @Override public Optional<com.vokerg.voktrader.marketdata.FillEstimate> estimateTakerSell(BigDecimal shares) { return Optional.empty(); }
        @Override public Optional<com.vokerg.voktrader.economy.FeeEstimate> estimateTakerFee(com.vokerg.voktrader.marketdata.FillEstimate estimate) { return Optional.empty(); }
        @Override public Optional<com.vokerg.voktrader.economy.FeeEstimate> estimateMakerBuyFee(BigDecimal amountUsd) { return Optional.empty(); }
    }
}
