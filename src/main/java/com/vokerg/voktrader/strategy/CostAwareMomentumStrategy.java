package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.paper.PaperTradeFeeCalculator;
import com.vokerg.voktrader.paper.SignalEntity;
import com.vokerg.voktrader.paper.SignalService;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
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
public class CostAwareMomentumStrategy implements TradingStrategy {

    public static final String ID = "cost-aware-momentum-paper";

    private static final BigDecimal MIN_MID_SUM = new BigDecimal("0.97");
    private static final BigDecimal MAX_MID_SUM = new BigDecimal("1.03");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(10);
    private static final Duration SHARP_REVERSAL_WINDOW = Duration.ofSeconds(3);

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final StrategyTimeWindow strategyTimeWindow;
    private final SignalService signalService;
    private final StrategyProperties strategyProperties;
    private final PaperTradeFeeCalculator paperTradeFeeCalculator;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> trailingPeakBidBySignal = new ConcurrentHashMap<>();

    @Autowired
    public CostAwareMomentumStrategy(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            SignalService signalService,
            StrategyProperties strategyProperties,
            PaperTradeFeeCalculator paperTradeFeeCalculator
    ) {
        this(
                latestPriceState,
                trackedMarketState,
                strategyTimeWindow,
                signalService,
                strategyProperties,
                paperTradeFeeCalculator,
                Clock.systemUTC()
        );
    }

    CostAwareMomentumStrategy(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            SignalService signalService,
            StrategyProperties strategyProperties,
            PaperTradeFeeCalculator paperTradeFeeCalculator,
            Clock clock
    ) {
        this.latestPriceState = latestPriceState;
        this.trackedMarketState = trackedMarketState;
        this.strategyTimeWindow = strategyTimeWindow;
        this.signalService = signalService;
        this.strategyProperties = strategyProperties;
        this.paperTradeFeeCalculator = paperTradeFeeCalculator;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        if (!strategyTimeWindow.isInsideTradingWindow()) {
            return;
        }

        trySellOpenSignals();
        tryBuySignal();
    }

    private void trySellOpenSignals() {
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        var config = strategyProperties.costAwareMomentumOrDefault();

        for (SignalEntity signal : signalService.openPaperSignals(ID)) {
            if (!market.id().equals(signal.getMarketId())) {
                continue;
            }

            OutcomePrice price = latestPriceState.byTokenId(signal.getTokenId()).orElse(null);

            if (!hasCompletePrice(price)) {
                continue;
            }

            recordSample(price);

            BigDecimal mid = mid(price);
            BigDecimal paperPnl = paperTradeFeeCalculator.calculateExitPnl(
                    signal.getShares(),
                    price.bid(),
                    signal.getSizeUsd(),
                    signal.getFeeRate()
            );
            boolean profitable = paperPnl.compareTo(BigDecimal.ZERO) > 0;

            if (isNearExpiry(market, config)) {
                if (profitable) {
                    trailingPeakBidBySignal.remove(trailingKey(signal));
                    signalService.sellOpenPaperSignal(signal.getId(), price, "near expiry profitable paper exit");
                }
                continue;
            }

            BigDecimal priceMove = price.bid().subtract(signal.getEntryPrice());
            boolean takeProfitReached = paperPnl.compareTo(config.minProfitUsdOrDefault()) >= 0
                    || priceMove.compareTo(config.minPriceMoveOrDefault()) >= 0;

            if (takeProfitReached || trailingPeakBidBySignal.containsKey(trailingKey(signal))) {
                if (shouldSellTrailingStop(signal, price.bid(), config)) {
                    trailingPeakBidBySignal.remove(trailingKey(signal));
                    signalService.sellOpenPaperSignal(signal.getId(), price, "cost-aware momentum trailing stop");
                }
                continue;
            }

            Optional<BigDecimal> tenSecondMidMove = moveSince(signal.getTokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID);
            boolean heldLongEnoughForStop = hasHeldLongEnoughForStop(signal, config);
            boolean actualMomentumReversal = tenSecondMidMove
                    .map(move -> move.compareTo(new BigDecimal("-0.025")) < 0)
                    .orElse(false);
            boolean lossLimitHit = paperPnl.compareTo(config.maxLossUsdOrDefault().negate()) <= 0;
            boolean stopMidHit = mid.compareTo(config.stopMidOrDefault()) < 0;

            if (heldLongEnoughForStop
                    && actualMomentumReversal
                    && (lossLimitHit || stopMidHit)) {
                trailingPeakBidBySignal.remove(trailingKey(signal));
                signalService.sellOpenPaperSignal(signal.getId(), price, "cost-aware momentum stop loss");
            }
        }
    }

    private void tryBuySignal() {
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        OutcomePrice up = latestPriceState.byOutcome("Up").orElse(null);
        OutcomePrice down = latestPriceState.byOutcome("Down").orElse(null);

        if (!hasCompletePrice(up) || !hasCompletePrice(down)) {
            return;
        }

        recordSample(up);
        recordSample(down);

        var config = strategyProperties.costAwareMomentumOrDefault();
        Instant now = clock.instant();

        if (isStale(up, now, config) || isStale(down, now, config)) {
            return;
        }

        BigDecimal upMid = mid(up);
        BigDecimal downMid = mid(down);
        BigDecimal midSum = upMid.add(downMid);

        if (midSum.compareTo(MIN_MID_SUM) < 0 || midSum.compareTo(MAX_MID_SUM) > 0) {
            return;
        }

        OutcomePrice candidate = upMid.compareTo(downMid) >= 0 ? up : down;
        OutcomePrice opposite = candidate == up ? down : up;
        BigDecimal candidateMid = candidate == up ? upMid : downMid;
        BigDecimal oppositeMid = opposite == up ? upMid : downMid;

        if (candidate.spread().compareTo(config.maxSpreadOrDefault()) > 0
                || candidateMid.compareTo(config.minMidOrDefault()) < 0
                || candidate.ask().compareTo(config.maxEntryAskOrDefault()) > 0
                || candidate.bid().compareTo(config.minBidOrDefault()) < 0
                || oppositeMid.compareTo(config.maxOppositeMidOrDefault()) > 0) {
            return;
        }

        Optional<BigDecimal> midMove10s = moveSince(candidate.tokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID);
        Optional<BigDecimal> bidMove10s = moveSince(candidate.tokenId(), MID_MOMENTUM_WINDOW, SampleValue.BID);
        Optional<BigDecimal> midMove3s = moveSince(candidate.tokenId(), SHARP_REVERSAL_WINDOW, SampleValue.MID);

        if (midMove10s.isEmpty()
                || bidMove10s.isEmpty()
                || midMove10s.get().compareTo(config.minMidMove10sOrDefault()) < 0
                || bidMove10s.get().compareTo(config.minBidMove10sOrDefault()) < 0
                || bidMove10s.get().compareTo(candidate.spread()) <= 0
                || midMove3s.map(move -> move.compareTo(config.maxNegativeMove3sOrDefault()) <= 0).orElse(false)) {
            return;
        }

        boolean alreadyHasOpenSignalForThisMarket = signalService.openPaperSignals(ID)
                .stream()
                .anyMatch(signal -> market.id().equals(signal.getMarketId()));

        if (alreadyHasOpenSignalForThisMarket) {
            return;
        }

        signalService.createPaperBuySignal(
                market,
                candidate,
                config.paperSizeUsdOrDefault(),
                ID,
                "cost-aware momentum: stronger side with positive 10s move"
        );
    }

    private boolean hasCompletePrice(OutcomePrice price) {
        return price != null
                && price.bid() != null
                && price.ask() != null
                && price.spread() != null
                && price.updatedAt() != null;
    }

    private boolean isStale(
            OutcomePrice price,
            Instant now,
            StrategyProperties.CostAwareMomentum config
    ) {
        return Duration.between(price.updatedAt(), now).toMillis() > config.maxDataAgeMsOrDefault();
    }

    private boolean isNearExpiry(
            GammaMarketDto market,
            StrategyProperties.CostAwareMomentum config
    ) {
        return market.endDate() != null
                && Duration.between(clock.instant(), market.endDate()).compareTo(
                Duration.ofSeconds(config.forceDecisionSecondsOrDefault())
        ) < 0;
    }

    private boolean hasHeldLongEnoughForStop(
            SignalEntity signal,
            StrategyProperties.CostAwareMomentum config
    ) {
        return signal.getCreatedAt() != null
                && Duration.between(signal.getCreatedAt(), clock.instant()).compareTo(
                Duration.ofSeconds(config.minHoldSecondsOrDefault())
        ) >= 0;
    }

    private boolean shouldSellTrailingStop(
            SignalEntity signal,
            BigDecimal bid,
            StrategyProperties.CostAwareMomentum config
    ) {
        String key = trailingKey(signal);
        BigDecimal previousPeak = trailingPeakBidBySignal.get(key);

        if (previousPeak == null) {
            trailingPeakBidBySignal.put(key, bid);
            return false;
        }

        BigDecimal newPeak = previousPeak.max(bid);
        trailingPeakBidBySignal.put(key, newPeak);

        return bid.compareTo(newPeak.subtract(config.trailingStopBidDropOrDefault())) <= 0;
    }

    private String trailingKey(SignalEntity signal) {
        if (signal.getId() != null) {
            return "id:" + signal.getId();
        }

        return "signal:" + signal.getMarketId() + ":" + signal.getTokenId() + ":" + signal.getRuleName();
    }

    private BigDecimal mid(OutcomePrice price) {
        return price.bid()
                .add(price.ask())
                .divide(TWO, 8, RoundingMode.HALF_UP);
    }

    private void recordSample(OutcomePrice price) {
        Deque<PriceSample> samples = samplesByTokenId.computeIfAbsent(
                price.tokenId(),
                ignored -> new ArrayDeque<>()
        );
        samples.addLast(new PriceSample(price.bid(), price.ask(), mid(price), price.updatedAt()));

        Instant cutoff = clock.instant().minus(SAMPLE_WINDOW);
        while (!samples.isEmpty() && samples.peekFirst().updatedAt().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    private Optional<BigDecimal> moveSince(
            String tokenId,
            Duration window,
            SampleValue sampleValue
    ) {
        Deque<PriceSample> samples = samplesByTokenId.get(tokenId);

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
