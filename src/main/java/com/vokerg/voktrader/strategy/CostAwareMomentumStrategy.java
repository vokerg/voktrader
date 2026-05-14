package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.time.TimeMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CostAwareMomentumStrategy implements TradingStrategy {

    public static final String ID = "cost-aware-momentum-paper";

    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(10);
    private static final Duration SHARP_REVERSAL_WINDOW = Duration.ofSeconds(3);

    /*
     * Important for replay:
     *
     * The original moveSince implementation used the newest sample at or before
     * targetTime. If replay data was sparse, a "10s move" could silently become
     * a 20s-30s move, and a "3s reversal" could become a much older move.
     *
     * This tolerance makes the strategy require a reasonably local comparison
     * sample. If your stored snapshot cadence is slower than this, increase this
     * value deliberately rather than allowing unbounded lookback.
     */
    private static final Duration MOMENTUM_SAMPLE_TOLERANCE = Duration.ofSeconds(2);

    private final StrategyEntrySupport entrySupport;
    private final StrategyExitSupport exitSupport;
    private final StrategyTradeSupport tradeSupport;
    private final StrategyProperties strategyProperties;
    private final Clock clock;

    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> trailingPeakBidByTrade = new ConcurrentHashMap<>();

    @Autowired
    public CostAwareMomentumStrategy(
            StrategyEntrySupport entrySupport,
            StrategyExitSupport exitSupport,
            StrategyTradeSupport tradeSupport,
            StrategyProperties strategyProperties
    ) {
        this(
                entrySupport,
                exitSupport,
                tradeSupport,
                strategyProperties,
                Clock.systemUTC()
        );
    }

    CostAwareMomentumStrategy(
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
                "Cost-aware momentum",
                "Active production candidate. Uses the shared strategy market view while preserving its original top-of-book behavior.",
                "Attempts to buy the stronger Up/Down side when price action is already moving in its favor and the opposite side is weak. "
                        + "It is intentionally momentum-biased: it does not try to catch the absolute bottom; it tries to join a move once bid and mid movement confirm it.",
                "Uses latest top-of-book bid/ask/spread for Up and Down, midpoint history sampled by the strategy, bot-scoped trade history, fee-aware exit economy, "
                        + "and market expiry timing. It does not require a full order book to enter.",
                "Chooses the side with the higher midpoint, then requires tight spread, minimum midpoint, maximum ask, minimum bid, weak opposite midpoint, positive 10-second mid move, "
                        + "positive 10-second bid move, and no sharp negative 3-second reversal. Entry is routed as a taker-style buy at observed ask.",
                "Uses fee-aware exit estimates. It prefers trailing-stop behavior after profit is reachable, can exit near expiry only when profitable, and can stop loss after minimum hold time "
                        + "when momentum reverses and loss/stop-mid conditions are met.",
                "Good for fast directional moves where the book top is clean and the stronger side keeps receiving bid support. Relatively simple, well tested, and does not depend on deep book availability.",
                "Weak when top-of-book lies about executable depth. It may buy into thin asks because it only checks spread and top ask, not how much size exists behind the ask. "
                        + "It can overreact to short-lived momentum bursts, struggles in choppy mean-reverting markets, and may miss early reversals because it waits for confirmation. "
                        + "Maker economics are not considered for entry, and taker slippage beyond best ask is invisible to this strategy.",
                "Tune max spread, min bid, min midpoint move, and trailing stop together. If order book data is reliable, prefer using OrderBookLiquidityStrategy for entries where fill quality matters."
        );
    }

    @Override
    public void tick() {
        trySellOpenSignals();
        tryBuySignal();
    }

    private void trySellOpenSignals() {
        var config = strategyProperties.costAwareMomentumOrDefault();

        exitSupport.evaluateCurrentMarketOpenTrades(
                ID,
                "cost-aware-momentum",
                config.minProfitUsdOrDefault(),
                this::recordSample,
                analysis -> exitReason(analysis, config)
        );
    }

    private Optional<String> exitReason(
            StrategyExitSupport.ExitAnalysis analysis,
            StrategyProperties.CostAwareMomentum config
    ) {
        TradeEntity trade = analysis.trade();
        OutcomePrice price = analysis.price();

        BigDecimal estimatedNetPnl = analysis.economy().estimatedNetPnlUsd();
        boolean profitTargetReached = analysis.economy().minimumProfitReached();

        if (isNearExpiry(analysis.market(), config)) {
            if (profitTargetReached) {
                clearTrailingState(trade);
                return Optional.of("near expiry profitable paper exit");
            }

            return Optional.empty();
        }

        BigDecimal priceMove = price.bid().subtract(trade.getEntryAvgPrice());
        boolean takeProfitReached = profitTargetReached
                && priceMove.compareTo(config.minPriceMoveOrDefault()) >= 0;

        if (takeProfitReached || trailingPeakBidByTrade.containsKey(trailingKey(trade))) {
            if (shouldSellTrailingStop(trade, price.bid(), config)) {
                clearTrailingState(trade);
                return Optional.of("cost-aware momentum trailing stop");
            }

            return Optional.empty();
        }

        Optional<BigDecimal> tenSecondMidMove = moveSince(
                trade.getTokenId(),
                MID_MOMENTUM_WINDOW,
                SampleValue.MID
        );

        boolean heldLongEnoughForStop = hasHeldLongEnoughForStop(trade, config);
        boolean actualMomentumReversal = tenSecondMidMove
                .map(move -> move.compareTo(new BigDecimal("-0.025")) < 0)
                .orElse(false);

        boolean lossLimitHit = estimatedNetPnl.compareTo(config.maxLossUsdOrDefault().negate()) <= 0;
        boolean stopMidHit = analysis.mid().compareTo(config.stopMidOrDefault()) < 0;

        if (heldLongEnoughForStop
                && actualMomentumReversal
                && (lossLimitHit || stopMidHit)) {
            clearTrailingState(trade);
            return Optional.of("cost-aware momentum stop loss");
        }

        return Optional.empty();
    }

    private void tryBuySignal() {
        var config = strategyProperties.costAwareMomentumOrDefault();

        entrySupport.evaluateUpDownEntry(
                ID,
                "cost-aware-momentum",
                new StrategyEntrySupport.EntryRules(
                        config.maxDataAgeMsOrDefault(),
                        config.closedTradeCooldownSecondsOrDefault(),
                        config.maxTradesPerMarketOrDefault()
                ),
                this::recordSample,
                context -> entrySignal(context, config)
        );
    }

    private Optional<StrategyEntrySupport.EntrySignal> entrySignal(
            StrategyEntrySupport.EntryContext context,
            StrategyProperties.CostAwareMomentum config
    ) {
        StrategyOutcomeView candidateView = context.upOutcome().mid().compareTo(context.downOutcome().mid()) >= 0
                ? context.upOutcome()
                : context.downOutcome();

        StrategyOutcomeView oppositeView = "Up".equalsIgnoreCase(candidateView.outcome())
                ? context.downOutcome()
                : context.upOutcome();

        OutcomePrice candidate = candidateView.price();

        BigDecimal candidateMid = candidateView.mid();
        BigDecimal oppositeMid = oppositeView.mid();

        if (candidate.spread().compareTo(config.maxSpreadOrDefault()) > 0
                || candidateMid.compareTo(config.minMidOrDefault()) < 0
                || candidate.ask().compareTo(config.maxEntryAskOrDefault()) > 0
                || candidate.bid().compareTo(config.minBidOrDefault()) < 0
                || oppositeMid.compareTo(config.maxOppositeMidOrDefault()) > 0) {
            return Optional.empty();
        }

        Optional<BigDecimal> midMove10s = moveSince(
                candidate.tokenId(),
                MID_MOMENTUM_WINDOW,
                SampleValue.MID
        );

        Optional<BigDecimal> bidMove10s = moveSince(
                candidate.tokenId(),
                MID_MOMENTUM_WINDOW,
                SampleValue.BID
        );

        Optional<BigDecimal> midMove3s = moveSince(
                candidate.tokenId(),
                SHARP_REVERSAL_WINDOW,
                SampleValue.MID
        );

        if (midMove10s.isEmpty()
                || bidMove10s.isEmpty()
                || midMove10s.get().compareTo(config.minMidMove10sOrDefault()) < 0
                || bidMove10s.get().compareTo(config.minBidMove10sOrDefault()) < 0
                || bidMove10s.get().compareTo(candidate.spread()) <= 0
                || midMove3s.map(move -> move.compareTo(config.maxNegativeMove3sOrDefault()) <= 0).orElse(false)) {
            return Optional.empty();
        }

        return Optional.of(new StrategyEntrySupport.EntrySignal(
                candidate,
                config.paperSizeUsdOrDefault(),
                "cost-aware momentum: stronger side with positive 10s move"
        ));
    }

    private boolean isNearExpiry(
            GammaMarketDto market,
            StrategyProperties.CostAwareMomentum config
    ) {
        return market.endDate() != null
                && Duration.between(TimeMachine.now(clock), market.endDate())
                .compareTo(Duration.ofSeconds(config.forceDecisionSecondsOrDefault())) < 0;
    }

    private boolean hasHeldLongEnoughForStop(
            TradeEntity trade,
            StrategyProperties.CostAwareMomentum config
    ) {
        Instant heldSince = trade.getEntryCompletedAt() == null
                ? trade.getCreatedAt()
                : trade.getEntryCompletedAt();

        return heldSince != null
                && Duration.between(heldSince, TimeMachine.now(clock))
                .compareTo(Duration.ofSeconds(config.minHoldSecondsOrDefault())) >= 0;
    }

    private boolean shouldSellTrailingStop(
            TradeEntity trade,
            BigDecimal bid,
            StrategyProperties.CostAwareMomentum config
    ) {
        String key = trailingKey(trade);
        AtomicBoolean shouldSell = new AtomicBoolean(false);

        trailingPeakBidByTrade.compute(key, (ignored, previousPeak) -> {
            if (previousPeak == null) {
                return bid;
            }

            BigDecimal newPeak = previousPeak.max(bid);
            BigDecimal stopBid = newPeak.subtract(config.trailingStopBidDropOrDefault());

            if (bid.compareTo(stopBid) <= 0) {
                shouldSell.set(true);
            }

            return newPeak;
        });

        return shouldSell.get();
    }

    private void clearTrailingState(TradeEntity trade) {
        trailingPeakBidByTrade.remove(trailingKey(trade));
    }

    private String trailingKey(TradeEntity trade) {
        if (trade.getId() != null) {
            return scopeKey(trade.getBotId()) + ":id:" + trade.getId();
        }

        return scopeKey(trade.getBotId())
                + ":trade:"
                + trade.getMarketId()
                + ":"
                + trade.getTokenId()
                + ":"
                + trade.getRuleId();
    }

    private BigDecimal mid(OutcomePrice price) {
        return StrategyEntrySupport.mid(price);
    }

    private void recordSample(OutcomePrice price) {
        Deque<PriceSample> samples = samplesByTokenId.computeIfAbsent(
                sampleKey(price.tokenId()),
                ignored -> new ArrayDeque<>()
        );

        synchronized (samples) {
            samples.addLast(new PriceSample(
                    price.bid(),
                    price.ask(),
                    mid(price),
                    price.updatedAt()
            ));

            Instant cutoff = TimeMachine.now(clock).minus(SAMPLE_WINDOW);

            while (!samples.isEmpty() && samples.peekFirst().updatedAt().isBefore(cutoff)) {
                samples.removeFirst();
            }
        }
    }

    private Optional<BigDecimal> moveSince(
            String tokenId,
            Duration window,
            SampleValue sampleValue
    ) {
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
                .map(sample -> sampleValue.value(latest).subtract(sampleValue.value(sample)));
    }

    private String sampleKey(String tokenId) {
        return scopeKey(tradeSupport.currentBotId()) + ":" + tokenId;
    }

    private String scopeKey(Long botId) {
        return botId == null ? "default" : "bot:" + botId;
    }

    private record PriceSample(
            BigDecimal bid,
            BigDecimal ask,
            BigDecimal mid,
            Instant updatedAt
    ) {
    }

    private enum SampleValue {
        BID {
            @Override
            BigDecimal value(PriceSample sample) {
                return sample.bid();
            }
        },
        MID {
            @Override
            BigDecimal value(PriceSample sample) {
                return sample.mid();
            }
        };

        abstract BigDecimal value(PriceSample sample);
    }
}
