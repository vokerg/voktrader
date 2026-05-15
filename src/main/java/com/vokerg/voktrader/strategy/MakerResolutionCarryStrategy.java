package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.backtest.BacktestDiagnosticsContext;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.time.TimeMachine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MakerResolutionCarryStrategy implements TradingStrategy {
    public static final String ID = "maker-resolution-carry";

    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(5);
    private static final BigDecimal TWO = new BigDecimal("2");

    private final StrategyEntrySupport entrySupport;
    private final StrategyExitSupport exitSupport;
    private final StrategyTradeSupport tradeSupport;
    private final StrategyProperties strategyProperties;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();

    @Autowired
    public MakerResolutionCarryStrategy(
            StrategyEntrySupport entrySupport,
            StrategyExitSupport exitSupport,
            StrategyTradeSupport tradeSupport,
            StrategyProperties strategyProperties
    ) {
        this(entrySupport, exitSupport, tradeSupport, strategyProperties, Clock.systemUTC());
    }

    MakerResolutionCarryStrategy(
            StrategyEntrySupport entrySupport,
            StrategyExitSupport exitSupport,
            StrategyTradeSupport tradeSupport,
            StrategyProperties strategyProperties,
            Clock clock
    ) {
        this.entrySupport = entrySupport;
        this.exitSupport = exitSupport;
        this.tradeSupport = tradeSupport;
        this.strategyProperties = strategyProperties;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyDescription description() {
        return new StrategyDescription(
                "Maker resolution carry",
                "Example strategy for two newer strategy concepts: maker-style entry and explicit wait-for-resolution exit.",
                "Posts a maker-style GTC buy at the current bid when one side has mild positive momentum and visible bid support. Near expiry, it can choose settlement instead of selling.",
                "Uses StrategyMarketView, top-of-book price, order book age, near-top bid depth, midpoint history, GTC maker entry signals, fee-aware exit economy, and explicit ExitDecision values.",
                "Selects the stronger midpoint side, requires fresh book data, acceptable spread, bid under a configured maker cap, minimum bid, positive 5-second mid move, and near-top bid depth. "
                        + "It returns EntrySignal.makerBuy, which becomes a GTC post-only-style intent.",
                "Uses StrategyExitSupport.evaluateCurrentMarketOpenTradesWithDecision. If a profitable taker sell is available, it sells. If expiry is close and exit bid is poor, it returns WAIT_FOR_RESOLUTION. "
                        + "That means the strategy has deliberately chosen settlement as the exit path.",
                "Makes maker/taker distinction visible to future strategy authors and avoids hiding resolution carry inside an empty Optional. Good as a template for settlement-aware strategies.",
                "Weak because maker orders may not fill before the edge disappears, and resting orders need exchange reconciliation. "
                        + "Waiting for resolution can turn a mark-to-market loss into a full loss if the selected outcome resolves wrong. It is intentionally conservative and should start with small sizing.",
                "Tune maker bid cap, wait-for-resolution seconds, and near-bid depth together. Do not use resting orders without reconciliation and cancellation policy."
        );
    }

    @Override
    public void tick() {
        tryExit();
        tryEntry();
    }

    private void tryExit() {
        var config = strategyProperties.makerResolutionCarryOrDefault();
        exitSupport.evaluateCurrentMarketOpenTradesWithDecision(
                ID,
                "maker-resolution-carry",
                config.minProfitUsdOrDefault(),
                this::recordSample,
                analysis -> exitDecision(analysis, config)
        );
    }

    private Optional<StrategyExitSupport.ExitDecision> exitDecision(
            StrategyExitSupport.ExitAnalysis analysis,
            StrategyProperties.MakerResolutionCarry config
    ) {
        if (analysis.economy().minimumProfitReached()) {
            return Optional.of(StrategyExitSupport.ExitDecision.sellNow("maker carry profitable taker exit"));
        }
        if (analysis.canWaitForResolutionWithin(config.waitForResolutionSecondsOrDefault())
                && analysis.price().bid().compareTo(config.waitForResolutionBelowExitBidOrDefault()) <= 0) {
            return Optional.of(StrategyExitSupport.ExitDecision.waitForResolution(
                    "maker carry: exit bid poor near expiry; wait for market resolution"
            ));
        }
        return Optional.empty();
    }

    private void tryEntry() {
        var config = strategyProperties.makerResolutionCarryOrDefault();
        entrySupport.evaluateUpDownEntry(
                ID,
                "maker-resolution-carry",
                new StrategyEntrySupport.EntryRules(
                        config.maxDataAgeMsOrDefault(),
                        config.waitForResolutionSecondsOrDefault(),
                        config.maxTradesPerMarketOrDefault()
                ),
                this::recordSample,
                context -> entrySignal(context, config)
        );
    }

    private Optional<StrategyEntrySupport.EntrySignal> entrySignal(
            StrategyEntrySupport.EntryContext context,
            StrategyProperties.MakerResolutionCarry config
    ) {
        StrategyOutcomeView selected = context.upOutcome().mid().compareTo(context.downOutcome().mid()) >= 0
                ? context.upOutcome()
                : context.downOutcome();
        Optional<BigDecimal> midMove = moveSince(selected.tokenId(), MID_MOMENTUM_WINDOW);
        if (selected.orderBook().isEmpty()) {
            skip("no order book for selected outcome");
            return Optional.empty();
        }
        if (selected.bookAgeMs().map(age -> age > config.maxBookAgeMsOrDefault()).orElse(true)) {
            skip("book too stale or missing age");
            return Optional.empty();
        }
        if (selected.spread().compareTo(config.maxSpreadOrDefault()) > 0) {
            skip("spread too wide");
            return Optional.empty();
        }
        if (selected.price().bid().compareTo(config.minBidOrDefault()) < 0) {
            skip("bid below min bid");
            return Optional.empty();
        }
        if (selected.price().bid().compareTo(config.maxMakerBidOrDefault()) > 0) {
            skip("bid above max maker bid");
            return Optional.empty();
        }
        if (selected.bidDepthWithin(config.nearTopRangeOrDefault()).compareTo(config.minNearBidDepthSharesOrDefault()) < 0) {
            skip("near bid depth too low");
            return Optional.empty();
        }
        if (midMove.isEmpty()) {
            skip("missing 5s midpoint move");
            return Optional.empty();
        }
        if (midMove.get().compareTo(config.minMidMove5sOrDefault()) < 0) {
            skip("5s midpoint move below threshold");
            return Optional.empty();
        }
        return Optional.of(StrategyEntrySupport.EntrySignal.makerBuy(
                selected.price(),
                config.orderSizeUsdOrDefault(),
                "maker resolution carry: post maker bid on supported side"
        ));
    }

    private void skip(String reason) {
        BacktestDiagnosticsContext.recordStrategySkip(ID, reason);
    }

    private void recordSample(OutcomePrice price) {
        Deque<PriceSample> samples = samplesByTokenId.computeIfAbsent(sampleKey(price.tokenId()), ignored -> new ArrayDeque<>());
        samples.addLast(new PriceSample(mid(price), price.updatedAt()));
        Instant cutoff = TimeMachine.now(clock).minus(SAMPLE_WINDOW);
        while (!samples.isEmpty() && samples.peekFirst().updatedAt().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    private Optional<BigDecimal> moveSince(String tokenId, Duration window) {
        Deque<PriceSample> samples = samplesByTokenId.get(sampleKey(tokenId));
        if (samples == null || samples.size() < 2) {
            return Optional.empty();
        }
        PriceSample latest = samples.peekLast();
        Instant target = latest.updatedAt().minus(window);
        return samples.stream()
                .filter(sample -> !sample.updatedAt().isAfter(target))
                .max(Comparator.comparing(PriceSample::updatedAt))
                .map(sample -> latest.mid().subtract(sample.mid()));
    }

    private BigDecimal mid(OutcomePrice price) {
        return price.bid().add(price.ask()).divide(TWO, 8, RoundingMode.HALF_UP);
    }

    private String sampleKey(String tokenId) {
        Long botId = tradeSupport.currentBotId();
        return (botId == null ? "default" : "bot:" + botId) + ":" + tokenId;
    }

    private record PriceSample(BigDecimal mid, Instant updatedAt) {
    }
}
