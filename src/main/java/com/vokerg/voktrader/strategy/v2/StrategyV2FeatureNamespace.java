package com.vokerg.voktrader.strategy.v2;

import java.util.Set;

final class StrategyV2FeatureNamespace {
    private static final Set<String> FEATURES = Set.of(
            "market.id", "market.slug", "market.question", "market.seconds_to_expiry", "market.end_at",
            "market.is_binary", "market.is_active", "market.accepting_orders", "market.volume_usd_24h",
            "market.liquidity_usd", "market.mid_sum", "market.up_mid", "market.down_mid",
            "candidate.outcome", "candidate.token_id", "candidate.bid", "candidate.ask", "candidate.mid",
            "candidate.spread", "candidate.price_age_ms", "candidate.book_age_ms", "candidate.bid_depth_total",
            "candidate.ask_depth_total", "candidate.bid_depth_within_0_01", "candidate.bid_depth_within_0_02",
            "candidate.bid_depth_within_0_03", "candidate.bid_depth_within_0_05", "candidate.ask_depth_within_0_01",
            "candidate.ask_depth_within_0_02", "candidate.ask_depth_within_0_03", "candidate.ask_depth_within_0_05",
            "candidate.depth_imbalance_0_01", "candidate.depth_imbalance_0_03", "candidate.depth_imbalance_0_05",
            "candidate.mid_move_3s", "candidate.mid_move_5s", "candidate.mid_move_10s", "candidate.mid_move_30s",
            "candidate.bid_move_3s", "candidate.bid_move_5s", "candidate.bid_move_10s",
            "candidate.ask_move_3s", "candidate.ask_move_5s", "candidate.ask_move_10s",
            "candidate.spread_change_5s", "candidate.volatility_10s", "candidate.volatility_30s",
            "candidate.microprice", "candidate.microprice_edge", "candidate.mid_edge", "candidate.score",
            "opposite.outcome", "opposite.bid", "opposite.ask", "opposite.mid", "opposite.spread",
            "opposite.mid_move_5s", "opposite.bid_move_5s", "opposite.ask_move_5s", "opposite.depth_imbalance_0_03",
            "candidate.taker_buy.fillable", "candidate.taker_buy.avg_price", "candidate.taker_buy.worst_price",
            "candidate.taker_buy.slippage", "candidate.taker_buy.shares", "candidate.taker_buy.amount_usd",
            "candidate.taker_buy.fee_usd", "candidate.taker_buy.total_cost_usd",
            "candidate.taker_sell.fillable", "candidate.taker_sell.avg_price", "candidate.taker_sell.worst_price",
            "candidate.taker_sell.slippage", "candidate.taker_sell.fee_usd", "candidate.taker_sell.net_proceeds_usd",
            "candidate.maker_buy.price", "candidate.maker_buy.fee_usd", "candidate.maker_buy.queue_ahead_shares",
            "candidate.maker_buy.estimated_fill_probability", "candidate.maker_buy.expected_wait_seconds",
            "candidate.maker_buy.adverse_selection_risk", "candidate.maker_sell.price", "candidate.maker_sell.fee_usd",
            "candidate.maker_sell.queue_ahead_shares", "candidate.maker_sell.estimated_fill_probability",
            "candidate.maker_sell.expected_wait_seconds", "trade.id", "trade.entry_price", "trade.entry_shares",
            "trade.entry_amount_usd", "trade.entry_fee_usd", "trade.hold_seconds", "trade.current_bid",
            "trade.current_ask", "trade.current_mid", "trade.estimated_exit_fee_usd", "trade.estimated_net_pnl_usd",
            "trade.estimated_gross_pnl_usd", "trade.best_seen_bid", "trade.best_seen_mid", "trade.worst_seen_bid",
            "trade.max_favorable_excursion_usd", "trade.max_adverse_excursion_usd"
    );

    private StrategyV2FeatureNamespace() {
    }

    static boolean isKnown(String feature) {
        if (feature == null || feature.isBlank()) {
            return false;
        }
        String normalized = feature.trim();
        return FEATURES.contains(normalized)
                || normalized.startsWith("up.")
                || normalized.startsWith("down.")
                || normalized.contains(" ")
                || normalized.contains("-");
    }
}
