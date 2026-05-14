package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.backtest.BacktestDiagnosticsContext;
import com.vokerg.voktrader.economy.FeeEstimate;
import com.vokerg.voktrader.marketdata.FillEstimate;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResolutionPressureFokStrategy implements TradingStrategy {
    public static final String ID = "resolution-pressure-fok";

    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(5);
    private static final Duration NEGATIVE_GUARD_WINDOW = Duration.ofSeconds(3);

    /*
     * Prevent sparse replay data from turning a "5s move" into a much older move.
     * If your snapshot cadence is slower than expected, raise this deliberately.
     */
    private static final Duration MOMENTUM_SAMPLE_TOLERANCE = Duration.ofSeconds(2);

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int SCALE = 8;

    private final StrategyEntrySupport entrySupport;
    private final StrategyExitSupport exitSupport;
    private final StrategyTradeSupport tradeSupport;

    /*
     * Retained for constructor compatibility with the existing branch wiring.
     * The strategy currently consumes market state via EntryContext/ExitAnalysis.
     */
    @SuppressWarnings("unused")
    private final StrategyMarketDataProvider marketDataProvider;

    private final StrategyProperties strategyProperties;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();

    @Autowired
    public ResolutionPressureFokStrategy(
            StrategyEntrySupport entrySupport,
            StrategyExitSupport exitSupport,
            StrategyTradeSupport tradeSupport,
            StrategyMarketDataProvider marketDataProvider,
            StrategyProperties strategyProperties
    ) {
        this(entrySupport, exitSupport, tradeSupport, marketDataProvider, strategyProperties, Clock.systemUTC());
    }

    ResolutionPressureFokStrategy(
            StrategyEntrySupport entrySupport,
            StrategyExitSupport exitSupport,
            StrategyTradeSupport tradeSupport,
            StrategyMarketDataProvider marketDataProvider,
            StrategyProperties strategyProperties,
            Clock clock
    ) {
        this.entrySupport = entrySupport;
        this.exitSupport = exitSupport;
        this.tradeSupport = tradeSupport;
        this.marketDataProvider = marketDataProvider;
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
                "Resolution pressure FOK",
                "FOK/taker strategy for late binary markets where resolution risk is intentional rather than avoided.",
                "Buys the already-pressured side when midpoint edge, recent momentum, opposite-side weakness, and executable ask depth all agree.",
                "Uses StrategyMarketView and StrategyOutcomeView for top-of-book, seconds to expiry, full book age, near-ask depth, FOK taker buy estimates, and taker fee estimates.",
                "Routes FOK only. The entry price is the estimated taker average from walking the ask book, so backtests and live intent use the same fill-quality assumption.",
                "Exit sells immediately when fee-aware profit is available. Once inside the configured resolution window, it waits for resolution instead of locking in noisy taker losses.",
                "Best for testing the hypothesis that, in the last minute or so, paying taker fees can still work if the visible order book confirms pressure and there is no cheap opposite-side recovery.",
                "Weak when the market graph is driven by the underlying asset faster than Polymarket books update. It can still lose the full stake on resolution, by design.",
                "Tune min-mid-edge and max-entry-ask first. If backtests show too few trades, relax min-mid-move-5s or the seconds-to-expiry window before weakening book-fill checks."
        );
    }

    @Override
    public void tick() {
        tryExit();
        tryEntry();
    }

    private void tryExit() {
        var config = strategyProperties.resolutionPressureFokOrDefault();

        exitSupport.evaluateCurrentMarketOpenTradesWithDecision(
                ID,
                ID,
                config.minProfitUsdOrDefault(),
                this::recordSample,
                analysis -> exitDecision(analysis, config)
        );
    }

    private Optional<StrategyExitSupport.ExitDecision> exitDecision(
            StrategyExitSupport.ExitAnalysis analysis,
            StrategyProperties.ResolutionPressureFok config
    ) {
        if (analysis.economy().minimumProfitReached()) {
            return Optional.of(StrategyExitSupport.ExitDecision.sellNow(
                    "resolution pressure FOK: fee-aware profit"
            ));
        }

        /*
         * This is the important behavioral change.
         *
         * The strategy thesis is resolution pressure, not round-trip taker scalping.
         * Once inside the resolution window, do not sell merely because the current
         * taker exit estimate is ugly. Let the market resolve unless a fee-aware
         * profit exit is already available above.
         */
        if (analysis.canWaitForResolutionWithin(config.waitForResolutionSecondsOrDefault())
                && analysis.mid().compareTo(config.minMidOrDefault()) >= 0
                && analysis.economy().estimatedNetPnlUsd()
                        .compareTo(config.maxLossUsdOrDefault().negate()) > 0) {
            return Optional.of(StrategyExitSupport.ExitDecision.waitForResolution(
                    "resolution pressure FOK: near expiry, still strong enough to hold"
            ));
        }

        if (analysis.economy().estimatedNetPnlUsd()
                .compareTo(config.maxLossUsdOrDefault().negate()) <= 0) {
            return Optional.of(StrategyExitSupport.ExitDecision.sellNow(
                    "resolution pressure FOK: loss limit before resolution window"
            ));
        }

        return Optional.empty();
    }

    private void tryEntry() {
        var config = strategyProperties.resolutionPressureFokOrDefault();

        entrySupport.evaluateUpDownEntry(
                ID,
                ID,
                new StrategyEntrySupport.EntryRules(
                        config.maxDataAgeMsOrDefault(),
                        entryClosedTradeCooldownSeconds(config),
                        config.maxTradesPerMarketOrDefault()
                ),
                this::recordSample,
                context -> entrySignal(context, config)
        );
    }

    /*
     * Do not reuse wait-for-resolution-seconds here.
     *
     * wait-for-resolution-seconds is now an exit/hold parameter. Reusing it in
     * EntryRules couples "how long should I hold near expiry?" to "how long should
     * I suppress fresh entries after a close / near resolution guard?", which made
     * tuning ambiguous.
     *
     * This keeps the current property surface unchanged. The strategy's explicit
     * min/max seconds-to-expiry checks below remain the real entry window.
     */
    private long entryClosedTradeCooldownSeconds(StrategyProperties.ResolutionPressureFok config) {
        return config.minSecondsToExpiryOrDefault();
    }

    private Optional<StrategyEntrySupport.EntrySignal> entrySignal(
            StrategyEntrySupport.EntryContext context,
            StrategyProperties.ResolutionPressureFok config
    ) {
        long secondsToExpiry = context.marketView().secondsToExpiry().orElse(Long.MAX_VALUE);

        if (secondsToExpiry < config.minSecondsToExpiryOrDefault()) {
            skip("too close to expiry");
            return Optional.empty();
        }

        if (secondsToExpiry > config.maxSecondsToExpiryOrDefault()) {
            skip("too early before expiry");
            return Optional.empty();
        }

        Candidate up = candidate(context.upOutcome(), context.downOutcome(), config);
        Candidate down = candidate(context.downOutcome(), context.upOutcome(), config);
        Candidate selected = select(up, down);

        if (selected == null) {
            return Optional.empty();
        }

        OutcomePrice executablePrice = new OutcomePrice(
                selected.view().tokenId(),
                selected.view().outcome(),
                selected.view().price().bid(),
                selected.takerFill().averagePrice(),
                selected.takerFill().averagePrice().subtract(selected.view().price().bid()),
                selected.view().price().updatedAt()
        );

        return Optional.of(new StrategyEntrySupport.EntrySignal(
                executablePrice,
                config.paperSizeUsdOrDefault(),
                "resolution pressure FOK: late pressure, opposite weakness and executable taker depth"
        ));
    }

    private Candidate candidate(
            StrategyOutcomeView view,
            StrategyOutcomeView opposite,
            StrategyProperties.ResolutionPressureFok config
    ) {
        if (view.orderBook().isEmpty()) {
            skip("no order book");
            return null;
        }

        if (view.bookAgeMs().map(age -> age > config.maxBookAgeMsOrDefault()).orElse(true)) {
            skip("book too stale or missing age");
            return null;
        }

        if (view.spread().compareTo(config.maxSpreadOrDefault()) > 0) {
            skip("spread too wide");
            return null;
        }

        if (view.mid().compareTo(config.minMidOrDefault()) < 0) {
            skip("mid below threshold");
            return null;
        }

        if (view.price().ask().compareTo(config.maxEntryAskOrDefault()) > 0) {
            skip("ask above max entry");
            return null;
        }

        if (view.price().bid().compareTo(config.minBidOrDefault()) < 0) {
            skip("bid below threshold");
            return null;
        }

        if (opposite.mid().compareTo(config.maxOppositeMidOrDefault()) > 0) {
            skip("opposite mid too strong");
            return null;
        }

        BigDecimal edge = view.mid().subtract(opposite.mid());

        if (edge.compareTo(config.minMidEdgeOrDefault()) < 0) {
            skip("mid edge below threshold");
            return null;
        }

        Optional<BigDecimal> midMove = moveSince(view.tokenId(), MID_MOMENTUM_WINDOW);

        if (midMove.isEmpty()) {
            skip("missing 5s midpoint move");
            return null;
        }

        if (midMove.get().compareTo(config.minMidMove5sOrDefault()) < 0) {
            skip("5s midpoint move below threshold");
            return null;
        }

        Optional<BigDecimal> recentMove = moveSince(view.tokenId(), NEGATIVE_GUARD_WINDOW);

        if (recentMove.isPresent()
                && recentMove.get().compareTo(config.maxNegativeMove3sOrDefault()) < 0) {
            skip("recent negative move guard");
            return null;
        }

        if (view.askDepthWithin(config.nearTopRangeOrDefault())
                .compareTo(config.minNearAskDepthSharesOrDefault()) < 0) {
            skip("near ask depth too low");
            return null;
        }

        FillEstimate takerFill = view.estimateTakerBuy(config.paperSizeUsdOrDefault()).orElse(null);

        if (takerFill == null) {
            skip("missing taker fill estimate");
            return null;
        }

        FeeEstimate takerFee = view.estimateTakerFee(takerFill).orElse(null);

        if (takerFee == null) {
            skip("missing taker fee estimate");
            return null;
        }

        if (!takerFill.complete()
                || takerFill.averagePrice() == null
                || takerFill.worstPrice() == null) {
            skip("incomplete taker fill");
            return null;
        }

        if (takerFill.averagePrice()
                .subtract(view.price().ask())
                .compareTo(config.maxTakerSlippageOrDefault()) > 0) {
            skip("taker slippage too high");
            return null;
        }

        if (takerFill.worstPrice().compareTo(config.maxTakerWorstPriceOrDefault()) > 0) {
            skip("taker worst price too high");
            return null;
        }

        if (takerFee.feeUsd().compareTo(config.maxTakerFeeUsdOrDefault()) > 0) {
            skip("taker fee too high");
            return null;
        }

        return new Candidate(view, edge, midMove.get(), takerFill, takerFee);
    }

    private Candidate select(Candidate left, Candidate right) {
        if (left == null) {
            return right;
        }

        if (right == null) {
            return left;
        }

        int edgeCompare = left.edge().compareTo(right.edge());

        if (edgeCompare != 0) {
            return edgeCompare > 0 ? left : right;
        }

        int moveCompare = left.midMove().compareTo(right.midMove());

        if (moveCompare != 0) {
            return moveCompare > 0 ? left : right;
        }

        return left.takerFill().averagePrice().compareTo(right.takerFill().averagePrice()) <= 0
                ? left
                : right;
    }

    private void recordSample(OutcomePrice price) {
        Deque<PriceSample> samples = samplesByTokenId.computeIfAbsent(
                sampleKey(price.tokenId()),
                ignored -> new ArrayDeque<>()
        );

        synchronized (samples) {
            samples.addLast(new PriceSample(mid(price), price.updatedAt()));

            Instant cutoff = TimeMachine.now(clock).minus(SAMPLE_WINDOW);

            while (!samples.isEmpty() && samples.peekFirst().updatedAt().isBefore(cutoff)) {
                samples.removeFirst();
            }
        }
    }

    private Optional<BigDecimal> moveSince(String tokenId, Duration window) {
        Deque<PriceSample> samples = samplesByTokenId.get(sampleKey(tokenId));

        if (samples == null) {
            return Optional.empty();
        }

        List<PriceSample> snapshot;

        synchronized (samples) {
            if (samples.size() < 2) {
                return Optional.empty();
            }

            snapshot = new ArrayList<>(samples);
        }

        PriceSample latest = snapshot.get(snapshot.size() - 1);
        Instant target = latest.updatedAt().minus(window);
        Instant earliestAllowed = target.minus(MOMENTUM_SAMPLE_TOLERANCE);

        return snapshot.stream()
                .filter(sample -> !sample.updatedAt().isAfter(target))
                .filter(sample -> !sample.updatedAt().isBefore(earliestAllowed))
                .max(Comparator.comparing(PriceSample::updatedAt))
                .map(sample -> latest.mid().subtract(sample.mid()));
    }

    private BigDecimal mid(OutcomePrice price) {
        return price.bid().add(price.ask()).divide(TWO, SCALE, RoundingMode.HALF_UP);
    }

    private String sampleKey(String tokenId) {
        Long botId = tradeSupport.currentBotId();

        return (botId == null ? "default" : "bot:" + botId) + ":" + tokenId;
    }

    private void skip(String reason) {
        BacktestDiagnosticsContext.recordStrategySkip(ID, reason);
    }

    private record PriceSample(BigDecimal mid, Instant updatedAt) {
    }

    private record Candidate(
            StrategyOutcomeView view,
            BigDecimal edge,
            BigDecimal midMove,
            FillEstimate takerFill,
            FeeEstimate takerFee
    ) {
    }
}
