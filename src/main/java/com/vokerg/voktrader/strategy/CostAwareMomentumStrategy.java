package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.LatestPriceState;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.ExecutionRouter;
import com.vokerg.voktrader.trade.PaperFeeCalculator;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeRepository;
import com.vokerg.voktrader.trade.TradeStatus;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
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
    private final ExecutionRouter executionRouter;
    private final TradeRepository tradeRepository;
    private final StrategyProperties strategyProperties;
    private final PaperFeeCalculator paperFeeCalculator;
    private final TradingProperties tradingProperties;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> trailingPeakBidByTrade = new ConcurrentHashMap<>();

    @Autowired
    public CostAwareMomentumStrategy(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            ExecutionRouter executionRouter,
            TradeRepository tradeRepository,
            StrategyProperties strategyProperties,
            PaperFeeCalculator paperFeeCalculator,
            TradingProperties tradingProperties
    ) {
        this(
                latestPriceState,
                trackedMarketState,
                strategyTimeWindow,
                executionRouter,
                tradeRepository,
                strategyProperties,
                paperFeeCalculator,
                tradingProperties,
                Clock.systemUTC()
        );
    }

    CostAwareMomentumStrategy(
            LatestPriceState latestPriceState,
            TrackedMarketState trackedMarketState,
            StrategyTimeWindow strategyTimeWindow,
            ExecutionRouter executionRouter,
            TradeRepository tradeRepository,
            StrategyProperties strategyProperties,
            PaperFeeCalculator paperFeeCalculator,
            TradingProperties tradingProperties,
            Clock clock
    ) {
        this.latestPriceState = latestPriceState;
        this.trackedMarketState = trackedMarketState;
        this.strategyTimeWindow = strategyTimeWindow;
        this.executionRouter = executionRouter;
        this.tradeRepository = tradeRepository;
        this.strategyProperties = strategyProperties;
        this.paperFeeCalculator = paperFeeCalculator;
        this.tradingProperties = tradingProperties;
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

        for (TradeEntity trade : tradeRepository.findByStrategyIdAndStatus(ID, TradeStatus.OPEN)) {
            if (!market.id().equals(trade.getMarketId())) {
                continue;
            }

            OutcomePrice price = latestPriceState.byTokenId(trade.getTokenId()).orElse(null);

            if (!hasCompletePrice(price)) {
                continue;
            }

            recordSample(price);

            BigDecimal mid = mid(price);
            BigDecimal paperPnl = calculateExitPnl(trade, price.bid());
            boolean profitable = paperPnl.compareTo(BigDecimal.ZERO) > 0;

            if (isNearExpiry(market, config)) {
                if (profitable) {
                    trailingPeakBidByTrade.remove(trailingKey(trade));
                    routeSell(market, trade, price, "near expiry profitable paper exit");
                }
                continue;
            }

            BigDecimal priceMove = price.bid().subtract(trade.getEntryAvgPrice());
            boolean takeProfitReached = paperPnl.compareTo(config.minProfitUsdOrDefault()) >= 0
                    || priceMove.compareTo(config.minPriceMoveOrDefault()) >= 0;

            if (takeProfitReached || trailingPeakBidByTrade.containsKey(trailingKey(trade))) {
                if (shouldSellTrailingStop(trade, price.bid(), config)) {
                    trailingPeakBidByTrade.remove(trailingKey(trade));
                    routeSell(market, trade, price, "cost-aware momentum trailing stop");
                }
                continue;
            }

            Optional<BigDecimal> tenSecondMidMove = moveSince(trade.getTokenId(), MID_MOMENTUM_WINDOW, SampleValue.MID);
            boolean heldLongEnoughForStop = hasHeldLongEnoughForStop(trade, config);
            boolean actualMomentumReversal = tenSecondMidMove
                    .map(move -> move.compareTo(new BigDecimal("-0.025")) < 0)
                    .orElse(false);
            boolean lossLimitHit = paperPnl.compareTo(config.maxLossUsdOrDefault().negate()) <= 0;
            boolean stopMidHit = mid.compareTo(config.stopMidOrDefault()) < 0;

            if (heldLongEnoughForStop
                    && actualMomentumReversal
                    && (lossLimitHit || stopMidHit)) {
                trailingPeakBidByTrade.remove(trailingKey(trade));
                routeSell(market, trade, price, "cost-aware momentum stop loss");
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

        if (tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusOrderByCreatedAtDesc(
                ID,
                market.id(),
                TradeStatus.OPEN
        ).isPresent()) {
            return;
        }

        var result = executionRouter.route(TradeIntent.buy(
                market,
                candidate,
                config.paperSizeUsdOrDefault(),
                ID,
                "cost-aware-momentum",
                "cost-aware momentum: stronger side with positive 10s move"
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
            return "id:" + trade.getId();
        }

        return "trade:" + trade.getMarketId() + ":" + trade.getTokenId() + ":" + trade.getRuleId();
    }

    private BigDecimal calculateExitPnl(TradeEntity trade, BigDecimal exitPrice) {
        BigDecimal shares = trade.getEntryFilledShares() == null ? BigDecimal.ZERO : trade.getEntryFilledShares();
        BigDecimal exitFee = paperFeeCalculator.estimate(shares, exitPrice, tradingProperties.getPaperFeeRate());
        BigDecimal entryFee = trade.getEntryFeeUsd() == null ? BigDecimal.ZERO : trade.getEntryFeeUsd();
        BigDecimal entryCost = trade.getEntryFilledUsd() == null ? BigDecimal.ZERO : trade.getEntryFilledUsd();
        return shares
                .multiply(exitPrice)
                .subtract(exitFee)
                .subtract(entryCost)
                .subtract(entryFee)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private void routeSell(GammaMarketDto market, TradeEntity trade, OutcomePrice price, String reason) {
        var result = executionRouter.route(TradeIntent.sell(
                market,
                price,
                trade.getEntryFilledShares(),
                ID,
                "cost-aware-momentum",
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
