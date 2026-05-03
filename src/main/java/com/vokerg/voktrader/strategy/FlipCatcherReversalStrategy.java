package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradingProperties;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class FlipCatcherReversalStrategy implements TradingStrategy {

    public static final String ID = "flip-catcher-reversal";

    private static final BigDecimal MIN_MID_SUM = new BigDecimal("0.97");
    private static final BigDecimal MAX_MID_SUM = new BigDecimal("1.03");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(5);
    private static final Duration SHARP_REVERSAL_WINDOW = Duration.ofSeconds(3);

    private final LatestPriceState latestPriceState;
    private final TrackedMarketState trackedMarketState;
    private final StrategyTimeWindow strategyTimeWindow;
    private final ExecutionRouter executionRouter;
    private final StrategyTradeSupport tradeSupport;
    private final StrategyProperties strategyProperties;
    private final PolymarketFeeCalculator feeCalculator;
    private final TradingProperties tradingProperties;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> trailingPeakBidByTrade = new ConcurrentHashMap<>();

    @Autowired
    public FlipCatcherReversalStrategy(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            ExecutionRouter executionRouter,
            StrategyTradeSupport tradeSupport,
            StrategyProperties strategyProperties,
            PolymarketFeeCalculator feeCalculator,
            TradingProperties tradingProperties
    ) {
        this(
                latestPriceState,
                trackedMarketState,
                strategyTimeWindow,
                executionRouter,
                tradeSupport,
                strategyProperties,
                feeCalculator,
                tradingProperties,
                Clock.systemUTC()
        );
    }

    FlipCatcherReversalStrategy(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            ExecutionRouter executionRouter,
            StrategyTradeSupport tradeSupport,
            StrategyProperties strategyProperties,
            PolymarketFeeCalculator feeCalculator,
            TradingProperties tradingProperties,
            Clock clock
    ) {
        this.latestPriceState = latestPriceState;
        this.trackedMarketState = trackedMarketState;
        this.strategyTimeWindow = strategyTimeWindow;
        this.executionRouter = executionRouter;
        this.tradeSupport = tradeSupport;
        this.strategyProperties = strategyProperties;
        this.feeCalculator = feeCalculator;
        this.tradingProperties = tradingProperties;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        trySellOpenSignals();

        if (!strategyTimeWindow.isInsideTradingWindow()) {
            return;
        }

        tryBuySignal();
    }

    private void trySellOpenSignals() {
        GammaMarketDto market = trackedMarketState.currentMarket().orElse(null);

        if (market == null || market.id() == null) {
            return;
        }

        var config = strategyProperties.flipCatcherOrDefault();
        Long botId = tradeSupport.currentBotId();

        for (TradeEntity trade : tradeSupport.openTrades(ID, botId, market.id())) {
            OutcomePrice price = latestPriceState.byTokenId(trade.getTokenId()).orElse(null);

            if (!hasCompletePrice(price)) {
                continue;
            }

            recordSample(price);

            BigDecimal mid = mid(price);
            BigDecimal estimatedNetPnl = tradeSupport.estimateExitPnl(
                    trade,
                    price.bid(),
                    tradingProperties.getTakerFeeRate(),
                    feeCalculator
            );
            boolean profitTargetReached = estimatedNetPnl.compareTo(config.minProfitUsdOrDefault()) >= 0;

            if (isNearExpiry(market, config)) {
                if (profitTargetReached) {
                    trailingPeakBidByTrade.remove(trailingKey(trade));
                    routeSell(market, trade, price, "near expiry profitable flip-catcher exit");
                }
                continue;
            }

            BigDecimal priceMove = price.bid().subtract(trade.getEntryAvgPrice());
            boolean takeProfitReached = profitTargetReached
                    && priceMove.compareTo(config.minPriceMoveOrDefault()) >= 0;

            if (takeProfitReached || trailingPeakBidByTrade.containsKey(trailingKey(trade))) {
                if (shouldSellTrailingStop(trade, price.bid(), config)) {
                    trailingPeakBidByTrade.remove(trailingKey(trade));
                    routeSell(market, trade, price, "flip-catcher trailing stop");
                }
                continue;
            }

            Optional<BigDecimal> tenSecondMidMove = moveSince(trade.getTokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID);
            boolean heldLongEnoughForStop = hasHeldLongEnoughForStop(trade, config);
            boolean actualMomentumReversal = tenSecondMidMove
                    .map(move -> move.compareTo(new BigDecimal("-0.025")) < 0)
                    .orElse(false);
            boolean lossLimitHit = estimatedNetPnl.compareTo(config.maxLossUsdOrDefault().negate()) <= 0;
            boolean stopMidHit = mid.compareTo(config.stopMidOrDefault()) < 0;

            if (heldLongEnoughForStop
                    && actualMomentumReversal
                    && (lossLimitHit || stopMidHit)) {
                trailingPeakBidByTrade.remove(trailingKey(trade));
                routeSell(market, trade, price, "flip-catcher stop loss");
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

        var config = strategyProperties.flipCatcherOrDefault();
        Instant now = clock.instant();
        Long botId = tradeSupport.currentBotId();

        if (isStale(up, now, config) || isStale(down, now, config)) {
            return;
        }

        BigDecimal upMid = mid(up);
        BigDecimal downMid = mid(down);
        BigDecimal midSum = upMid.add(downMid);

        if (midSum.compareTo(MIN_MID_SUM) < 0 || midSum.compareTo(MAX_MID_SUM) > 0) {
            return;
        }

        Candidate upCandidate = flipCandidate(up, down, upMid);
        Candidate downCandidate = flipCandidate(down, up, downMid);
        Candidate selected = selectCandidate(upCandidate, downCandidate);
        if (selected == null) {
            return;
        }

        OutcomePrice candidate = selected.price();
        if (!entryPassesFlipFilters(selected, config)) {
            return;
        }

        if (tradeSupport.hasOpenTrade(ID, botId, market.id())) {
            return;
        }

        if (tradeSupport.marketTradeLimitReached(ID, botId, market.id(), config.maxTradesPerMarketOrDefault())
                || tradeSupport.closedTradeCooldownActive(
                ID,
                botId,
                market.id(),
                config.closedTradeCooldownSecondsOrDefault(),
                clock.instant()
        )) {
            return;
        }

        if (tradeSupport.sameOutcomeLossLockoutActive(ID, botId, market.id(), candidate.tokenId())) {
            return;
        }

        var result = executionRouter.route(TradeIntent.buy(
                market,
                candidate,
                config.paperSizeUsdOrDefault(),
                ID,
                "flip-catcher",
                "flip-catcher: midrange side accelerating while opposite weakens"
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
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
            StrategyProperties.FlipCatcher config
    ) {
        return Duration.between(price.updatedAt(), now).toMillis() > config.maxDataAgeMsOrDefault();
    }

    private boolean isNearExpiry(
            GammaMarketDto market,
            StrategyProperties.FlipCatcher config
    ) {
        return market.endDate() != null
                && Duration.between(clock.instant(), market.endDate()).compareTo(
                Duration.ofSeconds(config.forceDecisionSecondsOrDefault())
        ) < 0;
    }

    private boolean hasHeldLongEnoughForStop(
            TradeEntity trade,
            StrategyProperties.FlipCatcher config
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

    private void routeSell(GammaMarketDto market, TradeEntity trade, OutcomePrice price, String reason) {
        var result = executionRouter.route(TradeIntent.sell(
                market,
                price,
                trade.getEntryFilledShares(),
                ID,
                "flip-catcher",
                reason
        ));
        log.info(
                "TRADE INTENT ROUTED: accepted={} mode={} tradeId={} orderId={} tradeStatus={} orderStatus={} message={}",
                result.accepted(), result.mode(), result.tradeId(), result.orderId(), result.tradeStatus(), result.orderStatus(), result.message());
    }

    private BigDecimal mid(OutcomePrice price) {
        return price.bid()
                .add(price.ask())
                .divide(TWO, 8, RoundingMode.HALF_UP);
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

    private Candidate flipCandidate(OutcomePrice candidate, OutcomePrice opposite, BigDecimal candidateMid) {
        return new Candidate(
                candidate,
                opposite,
                candidateMid,
                mid(opposite),
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
