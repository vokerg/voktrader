package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.FeeEstimate;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.model.TradeEntity;

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
public class OrderBookLiquidityStrategy implements TradingStrategy {
    public static final String ID = "order-book-liquidity";

    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(30);
    private static final Duration MID_MOMENTUM_WINDOW = Duration.ofSeconds(5);
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int SCALE = 8;

    private final StrategyEntrySupport entrySupport;
    private final StrategyExitSupport exitSupport;
    private final StrategyTradeSupport tradeSupport;
    private final StrategyMarketDataProvider marketDataProvider;
    private final StrategyProperties strategyProperties;
    private final Clock clock;
    private final Map<String, Deque<PriceSample>> samplesByTokenId = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> trailingPeakBidByTrade = new ConcurrentHashMap<>();

    @Autowired
    public OrderBookLiquidityStrategy(
            StrategyEntrySupport entrySupport,
            StrategyExitSupport exitSupport,
            StrategyTradeSupport tradeSupport,
            StrategyMarketDataProvider marketDataProvider,
            StrategyProperties strategyProperties
    ) {
        this(entrySupport, exitSupport, tradeSupport, marketDataProvider, strategyProperties, Clock.systemUTC());
    }

    OrderBookLiquidityStrategy(
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
                "Order-book liquidity",
                "Active experimental strategy. Built as the preferred template for new order-book-aware strategy work.",
                "Trades only when the selected side has recent momentum and the visible order book suggests the intended taker-sized order can actually fill at acceptable quality.",
                "Uses the explicit StrategyMarketView and StrategyOutcomeView APIs: top-of-book bid/ask/spread, price age, full order book age, near-top bid/ask depth, taker fill estimates, "
                        + "taker fee estimates, maker fee estimates for comparison, bot-scoped trade history, and fee-aware exit economy.",
                "Requires current full-depth book data. It checks spread, bid/ask thresholds, positive 5-second mid move, near-top ask depth, near-top bid support, near-depth imbalance, "
                        + "complete taker fill for configured size, average taker price, worst taker level, and taker fee cap. It routes using estimated taker average price rather than blindly using best ask.",
                "Uses the common exit support with fee-aware exit estimates, a trailing-stop style profit exit, and a stop-loss path after minimum hold when mid and net PnL are both poor. "
                        + "Exit is still top-of-book bid based today, although StrategyOutcomeView exposes taker sell estimates for future improvement.",
                "Best suited for avoiding apparent wins that would fail in a thin real book. It is explicit about fill completeness, slippage, depth, and commissions. "
                        + "It is also the clearest starting point for AI agents because the strategy-facing API lists most available building blocks.",
                "Weak when the visible order book is stale, spoofed, or changes faster than the tick cadence. It may reject good trades if depth thresholds are too strict. "
                        + "It intentionally routes through the FOK/taker-style execution path even though maker estimates are available, because this strategy optimizes immediate fill quality. "
                        + "It can also overfit to near-top depth and ignore broader market structure unless additional signals are added.",
                "Tune near-top range, minimum near depths, maximum taker slippage, and worst-price cap together. Next improvement should be sell-side book-walk exits and maker-order support."
        );
    }

    @Override
    public void tick() {
        trySellOpenTrades();
        tryBuySignal();
    }

    private void trySellOpenTrades() {
        var config = strategyProperties.orderBookLiquidityOrDefault();
        exitSupport.evaluateCurrentMarketOpenTrades(
                ID,
                "order-book-liquidity",
                config.minProfitUsdOrDefault(),
                this::recordSample,
                analysis -> exitReason(analysis, config)
        );
    }

    private Optional<String> exitReason(
            StrategyExitSupport.ExitAnalysis analysis,
            StrategyProperties.OrderBookLiquidity config
    ) {
        TradeEntity trade = analysis.trade();
        StrategyOutcomeView outcomeView = marketDataProvider.currentUpDownMarket()
                .flatMap(view -> view.token(trade.getTokenId()))
                .orElse(null);
        if (outcomeView != null && outcomeView.bookAgeMs().map(age -> age > config.maxBookAgeMsOrDefault()).orElse(true)) {
            return Optional.empty();
        }

        if (analysis.economy().minimumProfitReached()
                && analysis.price().bid().subtract(trade.getEntryAvgPrice()).compareTo(config.minPriceMoveOrDefault()) >= 0) {
            if (shouldSellTrailingStop(trade, analysis.price().bid(), config)) {
                trailingPeakBidByTrade.remove(trailingKey(trade));
                return Optional.of("order-book liquidity trailing stop after fee-aware profit");
            }
            return Optional.empty();
        }

        boolean heldLongEnough = heldForAtLeast(trade, config.minHoldSecondsOrDefault());
        boolean stopMidHit = analysis.mid().compareTo(config.stopMidOrDefault()) < 0;
        boolean lossLimitHit = analysis.economy().estimatedNetPnlUsd().compareTo(config.maxLossUsdOrDefault().negate()) <= 0;
        if (heldLongEnough && stopMidHit && lossLimitHit) {
            trailingPeakBidByTrade.remove(trailingKey(trade));
            return Optional.of("order-book liquidity stop loss");
        }
        return Optional.empty();
    }

    private void tryBuySignal() {
        var config = strategyProperties.orderBookLiquidityOrDefault();
        entrySupport.evaluateUpDownEntry(
                ID,
                "order-book-liquidity",
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
            StrategyProperties.OrderBookLiquidity config
    ) {
        Candidate up = candidate(context.upOutcome(), config);
        Candidate down = candidate(context.downOutcome(), config);
        Candidate selected = select(up, down);
        if (selected == null) {
            return Optional.empty();
        }

        OutcomePrice executablePrice = new OutcomePrice(
                selected.price().tokenId(),
                selected.price().outcome(),
                selected.price().bid(),
                selected.takerFill().averagePrice(),
                selected.takerFill().averagePrice().subtract(selected.price().bid()),
                selected.price().updatedAt()
        );
        return Optional.of(new StrategyEntrySupport.EntrySignal(
                executablePrice,
                config.orderSizeUsdOrDefault(),
                "order-book liquidity: taker depth, fees, spread and near-book support passed"
        ));
    }

    private Candidate candidate(StrategyOutcomeView view, StrategyProperties.OrderBookLiquidity config) {
        OutcomePrice price = view.price();
        Optional<BigDecimal> midMove = moveSince(price.tokenId(), MID_MOMENTUM_WINDOW);
        FillEstimate takerFill = view.estimateTakerBuy(config.orderSizeUsdOrDefault()).orElse(null);
        FeeEstimate takerFee = takerFill == null ? null : view.estimateTakerFee(takerFill).orElse(null);
        FeeEstimate makerFee = view.estimateMakerBuyFee(config.orderSizeUsdOrDefault()).orElse(null);
        BigDecimal nearBidDepth = view.bidDepthWithin(config.nearTopRangeOrDefault());
        BigDecimal nearAskDepth = view.askDepthWithin(config.nearTopRangeOrDefault());
        BigDecimal nearImbalance = imbalance(nearBidDepth, nearAskDepth);

        if (view.orderBook().isEmpty()
                || view.bookAgeMs().map(age -> age > config.maxBookAgeMsOrDefault()).orElse(true)
                || price.spread().compareTo(config.maxSpreadOrDefault()) > 0
                || price.ask().compareTo(config.maxEntryAskOrDefault()) > 0
                || price.bid().compareTo(config.minBidOrDefault()) < 0
                || midMove.isEmpty()
                || midMove.get().compareTo(config.minMidMove5sOrDefault()) < 0
                || nearAskDepth.compareTo(config.minNearAskDepthSharesOrDefault()) < 0
                || nearBidDepth.compareTo(config.minNearBidDepthSharesOrDefault()) < 0
                || nearImbalance.compareTo(config.minNearDepthImbalanceOrDefault()) < 0
                || takerFill == null
                || takerFee == null
                || !takerFill.complete()
                || takerFill.averagePrice() == null
                || takerFill.worstPrice() == null
                || takerFill.averagePrice().subtract(price.ask()).compareTo(config.maxTakerSlippageOrDefault()) > 0
                || takerFill.worstPrice().compareTo(config.maxTakerWorstPriceOrDefault()) > 0
                || takerFee.feeUsd().compareTo(config.maxTakerFeeUsdOrDefault()) > 0) {
            return null;
        }

        return new Candidate(price, midMove.get(), takerFill, takerFee, makerFee, nearImbalance);
    }

    private Candidate select(Candidate left, Candidate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        int moveCompare = left.midMove().compareTo(right.midMove());
        if (moveCompare != 0) {
            return moveCompare > 0 ? left : right;
        }
        return left.takerFill().averagePrice().compareTo(right.takerFill().averagePrice()) <= 0 ? left : right;
    }

    private boolean shouldSellTrailingStop(
            TradeEntity trade,
            BigDecimal bid,
            StrategyProperties.OrderBookLiquidity config
    ) {
        String key = trailingKey(trade);
        BigDecimal previousPeak = trailingPeakBidByTrade.get(key);
        if (previousPeak == null) {
            trailingPeakBidByTrade.put(key, bid);
            return false;
        }
        BigDecimal newPeak = previousPeak.max(bid);
        trailingPeakBidByTrade.put(key, newPeak);
        return bid.compareTo(newPeak.subtract(config.maxSpreadOrDefault())) <= 0;
    }

    private boolean heldForAtLeast(TradeEntity trade, long seconds) {
        Instant heldSince = trade.getEntryCompletedAt() == null ? trade.getCreatedAt() : trade.getEntryCompletedAt();
        return heldSince != null
                && Duration.between(heldSince, TimeMachine.now(clock)).compareTo(Duration.ofSeconds(seconds)) >= 0;
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
        return price.bid().add(price.ask()).divide(TWO, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal imbalance(BigDecimal bidDepth, BigDecimal askDepth) {
        BigDecimal total = bidDepth.add(askDepth);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return bidDepth.subtract(askDepth).divide(total, SCALE, RoundingMode.HALF_UP);
    }

    private String sampleKey(String tokenId) {
        Long botId = tradeSupport.currentBotId();
        return (botId == null ? "default" : "bot:" + botId) + ":" + tokenId;
    }

    private String trailingKey(TradeEntity trade) {
        if (trade.getId() != null) {
            return "id:" + trade.getId();
        }
        return "trade:" + trade.getMarketId() + ":" + trade.getTokenId() + ":" + trade.getRuleId();
    }

    private record PriceSample(BigDecimal mid, Instant updatedAt) {
    }

    private record Candidate(
            OutcomePrice price,
            BigDecimal midMove,
            FillEstimate takerFill,
            FeeEstimate takerFee,
            FeeEstimate makerFee,
            BigDecimal nearImbalance
    ) {
    }
}
