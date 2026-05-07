package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.TradeEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
public class FlipCatcherReversalStrategy implements TradingStrategy {

    public static final String ID = "flip-catcher-reversal";

    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(5);
    private static final Duration SHARP_REVERSAL_WINDOW = Duration.ofSeconds(3);

    private final StrategyEntrySupport entrySupport;
    private final StrategyExitSupport exitSupport;
    private final StrategyTradeSupport tradeSupport;
    private final StrategyProperties strategyProperties;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> trailingPeakBidByTrade = new ConcurrentHashMap<>();

    @Autowired
    public FlipCatcherReversalStrategy(
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

    FlipCatcherReversalStrategy(
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
                "Flip-catcher reversal",
                "Active production candidate. Adjusted to use the shared strategy market view without changing its original behavior.",
                "Targets midrange reversals around the flip point. It looks for the side that was previously weaker or balanced, then starts accelerating while the opposite side weakens.",
                "Uses latest top-of-book bid/ask/spread, Up/Down midpoint history, candidate/opposite bid and mid movement, bot-scoped trade history, fee-aware exit economy, and expiry timing. "
                        + "It does not require full order book depth.",
                "Builds a candidate for both Up and Down, chooses the stronger recent mover, and requires the selected side to remain in a midrange band rather than already repriced too far. "
                        + "It requires candidate mid and bid acceleration, opposite mid/bid weakness, tight spread, acceptable ask, and no immediate sharp negative move.",
                "Similar to cost-aware momentum: fee-aware exit estimates, trailing stop after profit threshold, near-expiry profitable exit, and stop loss after minimum hold when reversal/loss conditions align.",
                "Useful when markets flip quickly around 0.45-0.55 and one side starts taking control before fully repricing. It can catch moves earlier than the higher-mid momentum strategy.",
                "Weak in noisy midrange chop because it deliberately operates near the indecision zone. False flips can trigger entries just before the market snaps back. "
                        + "Like cost-aware momentum, it sees only top-of-book quality, so it can underestimate slippage and liquidity gaps. It may also avoid strong late moves once price leaves the configured midrange.",
                "Tune candidate mid band and 5-second movement thresholds carefully. Wider mid bands increase opportunity but also false flips. If false fills or slippage dominate, add order-book gates or use OrderBookLiquidityStrategy."
        );
    }

    @Override
    public void tick() {
        trySellOpenSignals();
        tryBuySignal();
    }

    private void trySellOpenSignals() {
        var config = strategyProperties.flipCatcherOrDefault();
        exitSupport.evaluateCurrentMarketOpenTrades(
                ID,
                "flip-catcher",
                config.minProfitUsdOrDefault(),
                this::recordSample,
                analysis -> exitReason(analysis, config)
        );
    }

    private Optional<String> exitReason(
            StrategyExitSupport.ExitAnalysis analysis,
            StrategyProperties.FlipCatcher config
    ) {
        TradeEntity trade = analysis.trade();
        OutcomePrice price = analysis.price();
        BigDecimal estimatedNetPnl = analysis.economy().estimatedNetPnlUsd();
        boolean profitTargetReached = analysis.economy().minimumProfitReached();

        if (isNearExpiry(analysis.market(), config)) {
            if (profitTargetReached) {
                trailingPeakBidByTrade.remove(trailingKey(trade));
                return Optional.of("near expiry profitable flip-catcher exit");
            }
            return Optional.empty();
        }

        BigDecimal priceMove = price.bid().subtract(trade.getEntryAvgPrice());
        boolean takeProfitReached = profitTargetReached
                && priceMove.compareTo(config.minPriceMoveOrDefault()) >= 0;

        if (takeProfitReached || trailingPeakBidByTrade.containsKey(trailingKey(trade))) {
            if (shouldSellTrailingStop(trade, price.bid(), config)) {
                trailingPeakBidByTrade.remove(trailingKey(trade));
                return Optional.of("flip-catcher trailing stop");
            }
            return Optional.empty();
        }

        Optional<BigDecimal> tenSecondMidMove = moveSince(trade.getTokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID);
        boolean heldLongEnoughForStop = hasHeldLongEnoughForStop(trade, config);
        boolean actualMomentumReversal = tenSecondMidMove
                .map(move -> move.compareTo(new BigDecimal("-0.025")) < 0)
                .orElse(false);
        boolean lossLimitHit = estimatedNetPnl.compareTo(config.maxLossUsdOrDefault().negate()) <= 0;
        boolean stopMidHit = analysis.mid().compareTo(config.stopMidOrDefault()) < 0;

        if (heldLongEnoughForStop
                && actualMomentumReversal
                && (lossLimitHit || stopMidHit)) {
            trailingPeakBidByTrade.remove(trailingKey(trade));
            return Optional.of("flip-catcher stop loss");
        }

        return Optional.empty();
    }

    private void tryBuySignal() {
        var config = strategyProperties.flipCatcherOrDefault();
        entrySupport.evaluateUpDownEntry(
                ID,
                "flip-catcher",
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
            StrategyProperties.FlipCatcher config
    ) {
        Candidate upCandidate = flipCandidate(context.upOutcome(), context.downOutcome());
        Candidate downCandidate = flipCandidate(context.downOutcome(), context.upOutcome());
        Candidate selected = selectCandidate(upCandidate, downCandidate);
        if (selected == null) {
            return Optional.empty();
        }

        OutcomePrice candidate = selected.price();
        if (!entryPassesFlipFilters(selected, config)) {
            return Optional.empty();
        }

        return Optional.of(new StrategyEntrySupport.EntrySignal(
                candidate,
                config.paperSizeUsdOrDefault(),
                "flip-catcher: midrange side accelerating while opposite weakens"
        ));
    }

    private boolean isNearExpiry(
            GammaMarketDto market,
            StrategyProperties.FlipCatcher config
    ) {
        return market.endDate() != null
                && Duration.between(TimeMachine.now(clock), market.endDate()).compareTo(
                Duration.ofSeconds(config.forceDecisionSecondsOrDefault())
        ) < 0;
    }

    private boolean hasHeldLongEnoughForStop(
            TradeEntity trade,
            StrategyProperties.FlipCatcher config
    ) {
        Instant heldSince = trade.getEntryCompletedAt() == null ? trade.getCreatedAt() : trade.getEntryCompletedAt();
        return heldSince != null
                && Duration.between(heldSince, TimeMachine.now(clock)).compareTo(
                Duration.ofSeconds(config.minHoldSecondsOrDefault())
        ) >= 0;
    }

    private boolean shouldSellTrailingStop(
            TradeEntity trade,
            BigDecimal bid,
            StrategyProperties.FlipCatcher config
    ) {
        String key = trailingKey(trade);
        BigDecimal previousPeak = trailingPeakBidByTrade.get(key);

        if (previousPeak == null) {
            trailingPeakBidByTrade.put(key, bid);
            return false;
        }

        BigDecimal newPeak = previousPeak.max(bid);
        trailingPeakBidByTrade.put(key, newPeak);

        return bid.compareTo(newPeak.subtract(config.trailingStopBidDropOrDefault())) <= 0;
    }

    private String trailingKey(TradeEntity trade) {
        if (trade.getId() != null) {
            return scopeKey(trade.getBotId()) + ":id:" + trade.getId();
        }

        return scopeKey(trade.getBotId()) + ":trade:" + trade.getMarketId() + ":" + trade.getTokenId() + ":" + trade.getRuleId();
    }

    private BigDecimal mid(OutcomePrice price) {
        return StrategyEntrySupport.mid(price);
    }

    private void recordSample(OutcomePrice price) {
        Deque<PriceSample> samples = samplesByTokenId.computeIfAbsent(
                sampleKey(price.tokenId()),
                ignored -> new ArrayDeque<>()
        );
        samples.addLast(new PriceSample(price.bid(), price.ask(), mid(price), price.updatedAt()));

        Instant cutoff = TimeMachine.now(clock).minus(SAMPLE_WINDOW);
        while (!samples.isEmpty() && samples.peekFirst().updatedAt().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    private Optional<BigDecimal> moveSince(
            String tokenId,
            Duration window,
            SampleValue sampleValue
    ) {
        Deque<PriceSample> samples = samplesByTokenId.get(sampleKey(tokenId));

        if (samples == null || samples.size() < 2) {
            return Optional.empty();
        }

        PriceSample latest = samples.peekLast();
        Instant target = latest.updatedAt().minus(window);

        return samples.stream()
                .filter(sample -> !sample.updatedAt().isAfter(target))
                .max(Comparator.comparing(PriceSample::updatedAt))
                .map(sample -> sampleValue.value(latest).subtract(sampleValue.value(sample)));
    }

    private Candidate flipCandidate(StrategyOutcomeView candidateView, StrategyOutcomeView oppositeView) {
        OutcomePrice candidate = candidateView.price();
        OutcomePrice opposite = oppositeView.price();
        return new Candidate(
                candidate,
                opposite,
                candidateView.mid(),
                oppositeView.mid(),
                moveSince(candidate.tokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID),
                moveSince(candidate.tokenId(), MID_MOMENTUM_WINDOW, SampleValue.BID),
                moveSince(candidate.tokenId(), SHARP_REVERSAL_WINDOW, SampleValue.MID),
                moveSince(opposite.tokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID),
                moveSince(opposite.tokenId(), MID_MOMENTUM_WINDOW, SampleValue.BID)
        );
    }

    private Candidate selectCandidate(Candidate left, Candidate right) {
        if (left.midMove().isEmpty()) {
            return right.midMove().isPresent() ? right : null;
        }
        if (right.midMove().isEmpty()) {
            return left;
        }
        return left.midMove().get().compareTo(right.midMove().get()) >= 0 ? left : right;
    }

    private boolean entryPassesFlipFilters(Candidate candidate, StrategyProperties.FlipCatcher config) {
        OutcomePrice price = candidate.price();
        if (price.spread().compareTo(config.maxSpreadOrDefault()) > 0
                || price.ask().compareTo(config.maxEntryAskOrDefault()) > 0
                || candidate.mid().compareTo(config.minCandidateMidOrDefault()) < 0
                || candidate.mid().compareTo(config.maxCandidateMidOrDefault()) > 0) {
            return false;
        }
        if (candidate.midMove().isEmpty()
                || candidate.bidMove().isEmpty()
                || candidate.oppositeMidMove().isEmpty()
                || candidate.oppositeBidMove().isEmpty()) {
            return false;
        }
        return candidate.midMove().get().compareTo(config.minCandidateMidMove5sOrDefault()) >= 0
                && candidate.bidMove().get().compareTo(config.minCandidateBidMove5sOrDefault()) >= 0
                && candidate.bidMove().get().compareTo(price.spread()) > 0
                && candidate.oppositeMidMove().get().compareTo(config.maxOppositeMidMove5sOrDefault()) <= 0
                && candidate.oppositeBidMove().get().compareTo(config.maxOppositeBidMove5sOrDefault()) <= 0
                && !candidate.recentMidMove()
                .map(move -> move.compareTo(config.maxNegativeMove3sOrDefault()) <= 0)
                .orElse(false);
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

    private record Candidate(
            OutcomePrice price,
            OutcomePrice opposite,
            BigDecimal mid,
            BigDecimal oppositeMid,
            Optional<BigDecimal> midMove,
            Optional<BigDecimal> bidMove,
            Optional<BigDecimal> recentMidMove,
            Optional<BigDecimal> oppositeMidMove,
            Optional<BigDecimal> oppositeBidMove
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
