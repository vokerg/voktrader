package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
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
public class CostAwareMomentumStrategy implements TradingStrategy {

    public static final String ID = "cost-aware-momentum-paper";

    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(10);
    private static final Duration SHARP_REVERSAL_WINDOW = Duration.ofSeconds(3);

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
                trailingPeakBidByTrade.remove(trailingKey(trade));
                return Optional.of("near expiry profitable paper exit");
            }
            return Optional.empty();
        }

        BigDecimal priceMove = price.bid().subtract(trade.getEntryAvgPrice());
        boolean takeProfitReached = profitTargetReached
                && priceMove.compareTo(config.minPriceMoveOrDefault()) >= 0;

        if (takeProfitReached || trailingPeakBidByTrade.containsKey(trailingKey(trade))) {
            if (shouldSellTrailingStop(trade, price.bid(), config)) {
                trailingPeakBidByTrade.remove(trailingKey(trade));
                return Optional.of("cost-aware momentum trailing stop");
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
        OutcomePrice candidate = context.upMid().compareTo(context.downMid()) >= 0 ? context.up() : context.down();
        OutcomePrice opposite = candidate == context.up() ? context.down() : context.up();
        BigDecimal candidateMid = candidate == context.up() ? context.upMid() : context.downMid();
        BigDecimal oppositeMid = opposite == context.up() ? context.upMid() : context.downMid();

        if (candidate.spread().compareTo(config.maxSpreadOrDefault()) > 0
                || candidateMid.compareTo(config.minMidOrDefault()) < 0
                || candidate.ask().compareTo(config.maxEntryAskOrDefault()) > 0
                || candidate.bid().compareTo(config.minBidOrDefault()) < 0
                || oppositeMid.compareTo(config.maxOppositeMidOrDefault()) > 0) {
            return Optional.empty();
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
                && Duration.between(clock.instant(), market.endDate()).compareTo(
                Duration.ofSeconds(config.forceDecisionSecondsOrDefault())
        ) < 0;
    }

    private boolean hasHeldLongEnoughForStop(
            TradeEntity trade,
            StrategyProperties.CostAwareMomentum config
    ) {
        Instant heldSince = trade.getEntryCompletedAt() == null ? trade.getCreatedAt() : trade.getEntryCompletedAt();
        return heldSince != null
                && Duration.between(heldSince, clock.instant()).compareTo(
                Duration.ofSeconds(config.minHoldSecondsOrDefault())
        ) >= 0;
    }

    private boolean shouldSellTrailingStop(
            TradeEntity trade,
            BigDecimal bid,
            StrategyProperties.CostAwareMomentum config
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
