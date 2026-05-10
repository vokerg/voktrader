package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.economy.FeeEstimate;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyMarketView;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.OrderRuntimeState;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StrategyV2FeatureResolver {
    private static final int SCALE = 8;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal UNKNOWN_FEE_FALLBACK_RATE = new BigDecimal("0.072");
    private final Map<String, ArrayDeque<Sample>> samplesByToken = new ConcurrentHashMap<>();

    public List<StrategyV2FeatureContext> contexts(GammaMarketDto market, StrategyMarketView marketView, BigDecimal orderUsd) {
        return contexts(market, marketView, orderUsd, null);
    }

    public List<StrategyV2FeatureContext> contexts(
            GammaMarketDto market,
            StrategyMarketView marketView,
            BigDecimal orderUsd,
            StrategyRuntimeState runtimeState
    ) {
        Instant now = TimeMachine.now();
        marketView.outcomes().forEach(view -> record(view, now));
        return marketView.outcomes().stream()
                .map(candidate -> context(market, marketView, candidate, opposite(marketView, candidate), now, orderUsd, runtimeState))
                .toList();
    }

    public Object resolve(StrategyV2FeatureContext context, String featureOrExpression) {
        if (featureOrExpression == null || featureOrExpression.isBlank()) {
            return null;
        }
        Object direct = context.features().get(featureOrExpression);
        if (direct != null) {
            return direct;
        }
        return ExpressionEvaluator.evaluate(featureOrExpression, context.features());
    }

    private StrategyV2FeatureContext context(
            GammaMarketDto market,
            StrategyMarketView marketView,
            StrategyOutcomeView candidate,
            StrategyOutcomeView opposite,
            Instant now,
            BigDecimal orderUsd,
            StrategyRuntimeState runtimeState
    ) {
        Map<String, Object> features = new HashMap<>();
        putMarket(features, market, marketView, now);
        putOutcome(features, "candidate", candidate, opposite, orderUsd);
        putOutcome(features, "opposite", opposite, candidate, orderUsd);
        putAlias(features, "up", marketView.outcome("Up").orElse(null), marketView.outcome("Down").orElse(null));
        putAlias(features, "down", marketView.outcome("Down").orElse(null), marketView.outcome("Up").orElse(null));
        putRuntimeState(features, runtimeState, marketView, now);
        return new StrategyV2FeatureContext(market, marketView, candidate, opposite, now, features, runtimeState);
    }

    private void putRuntimeState(
            Map<String, Object> features,
            StrategyRuntimeState state,
            StrategyMarketView marketView,
            Instant now
    ) {
        if (state == null) {
            features.put("position.status", "NEW");
            features.put("position.has_position", false);
            features.put("position.fee_known", false);
            putTradeAliases(features);
            return;
        }
        features.put("position.status", state.currentTradeStatus() == null ? null : state.currentTradeStatus().name());
        features.put("position.has_position", state.hasPosition());
        features.put("position.filled_shares", state.filledShares());
        features.put("position.remaining_shares", state.remainingShares());
        features.put("position.avg_entry_price", state.avgEntryPrice());
        features.put("position.realized_fee_usd", state.realizedFeeUsd());
        features.put("position.fee_known", state.feeKnown());
        BigDecimal markPrice = markPrice(state, marketView);
        BigDecimal fallbackFee = estimatedUnknownFee(state);
        BigDecimal feeForPnl = state.feeKnown()
                ? nullToZero(state.realizedFeeUsd())
                : fallbackFee;
        BigDecimal pnl = unrealizedPnl(state, markPrice, feeForPnl);
        features.put("position.unrealized_pnl_usd", pnl);
        features.put("position.unrealized_pnl_pct", unrealizedPnlPct(state, pnl));
        features.put("position.entry_age_seconds", state.activeEntryOrder() == null ? null : state.activeEntryOrder().ageSeconds(now));
        features.put("position.exit_age_seconds", state.activeExitOrder() == null ? null : state.activeExitOrder().ageSeconds(now));
        putTradeAliases(features);
        putOrder(features, "entry_order", state.activeEntryOrder(), now);
        putOrder(features, "exit_order", state.activeExitOrder(), now);
    }

    private void putTradeAliases(Map<String, Object> features) {
        alias(features, "trade.estimated_net_pnl_usd", "position.unrealized_pnl_usd");
        alias(features, "trade.estimated_net_pnl_pct", "position.unrealized_pnl_pct");
        alias(features, "trade.hold_seconds", "position.entry_age_seconds");
        alias(features, "trade.filled_shares", "position.filled_shares");
        alias(features, "trade.avg_entry_price", "position.avg_entry_price");
        alias(features, "trade.realized_fee_usd", "position.realized_fee_usd");
        alias(features, "trade.fee_known", "position.fee_known");
    }

    private void alias(Map<String, Object> features, String alias, String source) {
        if (features.containsKey(source)) {
            features.put(alias, features.get(source));
        }
    }

    private void putOrder(Map<String, Object> features, String prefix, OrderRuntimeState order, Instant now) {
        features.put(prefix + ".status", order == null || order.status() == null ? null : order.status().name());
        features.put(prefix + ".age_seconds", order == null ? null : order.ageSeconds(now));
        features.put(prefix + ".requested_price", order == null ? null : order.requestedPrice());
        features.put(prefix + ".filled_shares", order == null ? null : order.filledShares());
        features.put(prefix + ".remaining_shares", order == null ? null : order.remainingShares());
        features.put(prefix + ".last_rejection_reason", order == null ? null : order.lastFailureReason());
    }

    private BigDecimal markPrice(StrategyRuntimeState state, StrategyMarketView marketView) {
        if (state.tokenId() == null || marketView == null) {
            return null;
        }
        return marketView.token(state.tokenId())
                .map(view -> bid(view) == null ? view.mid() : bid(view))
                .orElse(null);
    }

    private BigDecimal unrealizedPnl(StrategyRuntimeState state, BigDecimal markPrice, BigDecimal feeUsd) {
        if (state.filledShares() == null || state.avgEntryPrice() == null || markPrice == null) {
            return null;
        }
        BigDecimal entryCost = state.avgEntryPrice().multiply(state.filledShares());
        BigDecimal markValue = markPrice.multiply(state.filledShares());
        return markValue.subtract(entryCost).subtract(nullToZero(feeUsd));
    }

    private BigDecimal unrealizedPnlPct(StrategyRuntimeState state, BigDecimal pnl) {
        if (pnl == null || state.filledShares() == null || state.avgEntryPrice() == null) {
            return null;
        }
        BigDecimal entryCost = state.avgEntryPrice().multiply(state.filledShares());
        if (entryCost.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return pnl.divide(entryCost, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal estimatedUnknownFee(StrategyRuntimeState state) {
        if (state.filledShares() == null || state.avgEntryPrice() == null) {
            return BigDecimal.ZERO;
        }
        return state.filledShares().multiply(state.avgEntryPrice()).multiply(UNKNOWN_FEE_FALLBACK_RATE);
    }

    private void putMarket(Map<String, Object> features, GammaMarketDto market, StrategyMarketView marketView, Instant now) {
        features.put("market.id", market.id());
        features.put("market.slug", market.slug());
        features.put("market.question", market.question());
        features.put("market.end_at", market.endDate());
        features.put("market.seconds_to_expiry", market.endDate() == null ? null : Duration.between(now, market.endDate()).toSeconds());
        features.put("market.is_binary", marketView.outcomes().size() == 2);
        features.put("market.is_active", true);
        features.put("market.accepting_orders", market.acceptingOrders());
        BigDecimal upMid = marketView.outcome("Up").map(StrategyOutcomeView::mid).orElse(null);
        BigDecimal downMid = marketView.outcome("Down").map(StrategyOutcomeView::mid).orElse(null);
        features.put("market.up_mid", upMid);
        features.put("market.down_mid", downMid);
        features.put("market.mid_sum", upMid == null || downMid == null ? null : upMid.add(downMid));
    }

    private void putOutcome(Map<String, Object> features, String prefix, StrategyOutcomeView view, StrategyOutcomeView other, BigDecimal orderUsd) {
        if (view == null) {
            return;
        }
        features.put(prefix + ".outcome", view.outcome());
        features.put(prefix + ".token_id", view.tokenId());
        features.put(prefix + ".bid", bid(view));
        features.put(prefix + ".ask", ask(view));
        features.put(prefix + ".mid", view.mid());
        features.put(prefix + ".spread", view.spread());
        features.put(prefix + ".price_age_ms", view.priceAgeMs().orElse(null));
        features.put(prefix + ".book_age_ms", view.bookAgeMs().orElse(null));
        features.put(prefix + ".bid_depth_total", view.bidDepth());
        features.put(prefix + ".ask_depth_total", view.askDepth());
        putBands(features, prefix, view);
        features.put(prefix + ".mid_move_3s", move(view.tokenId(), "mid", 3));
        features.put(prefix + ".mid_move_5s", move(view.tokenId(), "mid", 5));
        features.put(prefix + ".mid_move_10s", move(view.tokenId(), "mid", 10));
        features.put(prefix + ".mid_move_30s", move(view.tokenId(), "mid", 30));
        features.put(prefix + ".bid_move_3s", move(view.tokenId(), "bid", 3));
        features.put(prefix + ".bid_move_5s", move(view.tokenId(), "bid", 5));
        features.put(prefix + ".bid_move_10s", move(view.tokenId(), "bid", 10));
        features.put(prefix + ".ask_move_3s", move(view.tokenId(), "ask", 3));
        features.put(prefix + ".ask_move_5s", move(view.tokenId(), "ask", 5));
        features.put(prefix + ".ask_move_10s", move(view.tokenId(), "ask", 10));
        features.put(prefix + ".spread_change_5s", move(view.tokenId(), "spread", 5));
        features.put(prefix + ".volatility_10s", volatility(view.tokenId(), 10));
        features.put(prefix + ".volatility_30s", volatility(view.tokenId(), 30));
        features.put(prefix + ".microprice", microprice(view));
        features.put(prefix + ".microprice_edge", value(features.get(prefix + ".microprice")).subtract(nullToZero(view.mid())));
        features.put(prefix + ".mid_edge", other == null ? null : view.mid().subtract(other.mid()));
        putTaker(features, prefix, view, orderUsd);
        features.put(prefix + ".maker_buy.price", features.get(prefix + ".bid"));
        view.estimateMakerBuyFee(orderUsd).map(FeeEstimate::feeUsd).ifPresent(fee -> features.put(prefix + ".maker_buy.fee_usd", fee));
    }

    private void putAlias(Map<String, Object> features, String alias, StrategyOutcomeView view, StrategyOutcomeView other) {
        if (view == null) {
            return;
        }
        features.put(alias + ".mid", view.mid());
        features.put(alias + ".spread", view.spread());
        features.put(alias + ".mid_edge", other == null ? null : view.mid().subtract(other.mid()));
        features.put(alias + ".mid_move_5s", move(view.tokenId(), "mid", 5));
        features.put(alias + ".depth_imbalance_0_03", depthImbalance(view, new BigDecimal("0.03")));
        features.put(alias + ".bid_depth_within_0_03", view.bidDepthWithin(new BigDecimal("0.03")));
        features.put(alias + ".ask_depth_within_0_03", view.askDepthWithin(new BigDecimal("0.03")));
    }

    private void putBands(Map<String, Object> features, String prefix, StrategyOutcomeView view) {
        for (String band : List.of("0.01", "0.02", "0.03", "0.05")) {
            BigDecimal range = new BigDecimal(band);
            String suffix = band.replace(".", "_");
            features.put(prefix + ".bid_depth_within_" + suffix, view.bidDepthWithin(range));
            features.put(prefix + ".ask_depth_within_" + suffix, view.askDepthWithin(range));
            features.put(prefix + ".depth_imbalance_" + suffix, depthImbalance(view, range));
        }
    }

    private void putTaker(Map<String, Object> features, String prefix, StrategyOutcomeView view, BigDecimal orderUsd) {
        Optional<FillEstimate> buy = view.estimateTakerBuy(orderUsd);
        buy.ifPresent(estimate -> {
            features.put(prefix + ".taker_buy.fillable", estimate.complete());
            features.put(prefix + ".taker_buy.avg_price", estimate.averagePrice());
            features.put(prefix + ".taker_buy.worst_price", estimate.worstPrice());
            features.put(prefix + ".taker_buy.shares", estimate.filledShares());
            features.put(prefix + ".taker_buy.amount_usd", estimate.notionalUsd());
            BigDecimal ask = ask(view) == null ? estimate.averagePrice() : ask(view);
            features.put(prefix + ".taker_buy.slippage", estimate.averagePrice() == null || ask == null ? null : estimate.averagePrice().subtract(ask));
            view.estimateTakerFee(estimate).map(FeeEstimate::feeUsd).ifPresent(fee -> {
                features.put(prefix + ".taker_buy.fee_usd", fee);
                features.put(prefix + ".taker_buy.total_cost_usd", estimate.notionalUsd().add(fee));
            });
        });
    }

    private void record(StrategyOutcomeView view, Instant now) {
        BigDecimal bid = bid(view);
        BigDecimal ask = ask(view);
        BigDecimal spread = bid == null || ask == null ? null : ask.subtract(bid);
        Sample sample = new Sample(now, view.mid(), bid, ask, spread);
        samplesByToken.compute(view.tokenId(), (ignored, deque) -> {
            ArrayDeque<Sample> samples = deque == null ? new ArrayDeque<>() : deque;
            samples.addLast(sample);
            Instant floor = now.minusSeconds(120);
            while (!samples.isEmpty() && samples.peekFirst().at().isBefore(floor)) {
                samples.removeFirst();
            }
            return samples;
        });
    }

    private StrategyOutcomeView opposite(StrategyMarketView marketView, StrategyOutcomeView candidate) {
        return marketView.outcomes().stream()
                .filter(view -> !view.tokenId().equals(candidate.tokenId()))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal move(String tokenId, String field, int seconds) {
        ArrayDeque<Sample> samples = samplesByToken.get(tokenId);
        if (samples == null || samples.size() < 2) {
            return BigDecimal.ZERO;
        }
        Sample latest = samples.peekLast();
        Instant target = latest.at().minusSeconds(seconds);
        Sample base = samples.stream()
                .filter(sample -> !sample.at().isAfter(target))
                .max(Comparator.comparing(Sample::at))
                .orElse(samples.peekFirst());
        return nullToZero(latest.value(field)).subtract(nullToZero(base.value(field)));
    }

    private BigDecimal volatility(String tokenId, int seconds) {
        ArrayDeque<Sample> samples = samplesByToken.get(tokenId);
        if (samples == null || samples.size() < 2) {
            return BigDecimal.ZERO;
        }
        Instant floor = samples.peekLast().at().minusSeconds(seconds);
        List<BigDecimal> mids = samples.stream()
                .filter(sample -> !sample.at().isBefore(floor))
                .map(Sample::mid)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (mids.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal min = mids.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = mids.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return max.subtract(min);
    }

    private BigDecimal depthImbalance(StrategyOutcomeView view, BigDecimal range) {
        BigDecimal bid = view.bidDepthWithin(range);
        BigDecimal ask = view.askDepthWithin(range);
        BigDecimal total = bid.add(ask);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return bid.subtract(ask).divide(total, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal microprice(StrategyOutcomeView view) {
        BigDecimal bid = bid(view);
        BigDecimal ask = ask(view);
        BigDecimal bidSize = view.bestBidLevel().map(OrderBookLevel::size).orElse(BigDecimal.ZERO);
        BigDecimal askSize = view.bestAskLevel().map(OrderBookLevel::size).orElse(BigDecimal.ZERO);
        BigDecimal total = bidSize.add(askSize);
        if (bid == null || ask == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return view.mid();
        }
        return bid.multiply(askSize).add(ask.multiply(bidSize)).divide(total, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal bid(StrategyOutcomeView view) {
        return view.bestBidLevel()
                .map(OrderBookLevel::price)
                .orElseGet(() -> view.mid() == null || view.spread() == null
                        ? null
                        : view.mid().subtract(view.spread().divide(TWO, SCALE, RoundingMode.HALF_UP)));
    }

    private BigDecimal ask(StrategyOutcomeView view) {
        return view.bestAskLevel()
                .map(OrderBookLevel::price)
                .orElseGet(() -> view.mid() == null || view.spread() == null
                        ? null
                        : view.mid().add(view.spread().divide(TWO, SCALE, RoundingMode.HALF_UP)));
    }

    private static BigDecimal value(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.ZERO;
    }

    private record Sample(Instant at, BigDecimal mid, BigDecimal bid, BigDecimal ask, BigDecimal spread) {
        BigDecimal value(String field) {
            return switch (field) {
                case "bid" -> bid;
                case "ask" -> ask;
                case "spread" -> spread;
                default -> mid;
            };
        }
    }
}
