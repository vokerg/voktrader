package com.vokerg.voktrader.strategy.v2;

import java.util.Set;

final class StrategyV2FeatureNamespace {
    private static final Set<String> FEATURES = Set.of(
            "market.id", "market.slug", "market.question", "market.seconds_to_expiry", "market.end_at",
            "market.is_binary", "market.is_active", "market.accepting_orders", "market.mid_sum",
            "market.up_mid", "market.down_mid",
            "candidate.outcome", "candidate.token_id", "candidate.bid", "candidate.ask", "candidate.mid",
            "candidate.spread", "candidate.price_age_ms", "candidate.book_age_ms", "candidate.bid_depth_total",
            "candidate.ask_depth_total", "candidate.bid_depth_within_0_01", "candidate.bid_depth_within_0_02",
            "candidate.bid_depth_within_0_03", "candidate.bid_depth_within_0_05", "candidate.ask_depth_within_0_01",
            "candidate.ask_depth_within_0_02", "candidate.ask_depth_within_0_03", "candidate.ask_depth_within_0_05",
            "candidate.depth_imbalance_0_01", "candidate.depth_imbalance_0_02",
            "candidate.depth_imbalance_0_03", "candidate.depth_imbalance_0_05",
            "candidate.mid_move_3s", "candidate.mid_move_5s", "candidate.mid_move_10s", "candidate.mid_move_30s",
            "candidate.bid_move_3s", "candidate.bid_move_5s", "candidate.bid_move_10s",
            "candidate.ask_move_3s", "candidate.ask_move_5s", "candidate.ask_move_10s",
            "candidate.spread_change_5s", "candidate.volatility_10s", "candidate.volatility_30s",
            "candidate.microprice", "candidate.microprice_edge", "candidate.mid_edge",
            "candidate.taker_buy.fillable", "candidate.taker_buy.avg_price", "candidate.taker_buy.worst_price",
            "candidate.taker_buy.slippage", "candidate.taker_buy.shares", "candidate.taker_buy.amount_usd",
            "candidate.taker_buy.fee_usd", "candidate.taker_buy.total_cost_usd",
            "candidate.maker_buy.price", "candidate.maker_buy.fee_usd",
            "opposite.outcome", "opposite.token_id", "opposite.bid", "opposite.ask", "opposite.mid",
            "opposite.spread", "opposite.price_age_ms", "opposite.book_age_ms", "opposite.bid_depth_total",
            "opposite.ask_depth_total", "opposite.bid_depth_within_0_01", "opposite.bid_depth_within_0_02",
            "opposite.bid_depth_within_0_03", "opposite.bid_depth_within_0_05", "opposite.ask_depth_within_0_01",
            "opposite.ask_depth_within_0_02", "opposite.ask_depth_within_0_03", "opposite.ask_depth_within_0_05",
            "opposite.depth_imbalance_0_01", "opposite.depth_imbalance_0_02",
            "opposite.depth_imbalance_0_03", "opposite.depth_imbalance_0_05",
            "opposite.mid_move_3s", "opposite.mid_move_5s", "opposite.mid_move_10s", "opposite.mid_move_30s",
            "opposite.bid_move_3s", "opposite.bid_move_5s", "opposite.bid_move_10s",
            "opposite.ask_move_3s", "opposite.ask_move_5s", "opposite.ask_move_10s",
            "opposite.spread_change_5s", "opposite.volatility_10s", "opposite.volatility_30s",
            "opposite.microprice", "opposite.microprice_edge", "opposite.mid_edge",
            "opposite.taker_buy.fillable", "opposite.taker_buy.avg_price", "opposite.taker_buy.worst_price",
            "opposite.taker_buy.slippage", "opposite.taker_buy.shares", "opposite.taker_buy.amount_usd",
            "opposite.taker_buy.fee_usd", "opposite.taker_buy.total_cost_usd",
            "opposite.maker_buy.price", "opposite.maker_buy.fee_usd",
            "trade.hold_seconds", "trade.estimated_net_pnl_usd", "trade.estimated_net_pnl_pct",
            "trade.filled_shares", "trade.avg_entry_price", "trade.realized_fee_usd", "trade.fee_known",
            "position.status", "position.has_position", "position.filled_shares",
            "position.remaining_shares", "position.avg_entry_price", "position.realized_fee_usd",
            "position.fee_known", "position.unrealized_pnl_usd", "position.unrealized_pnl_pct",
            "position.entry_age_seconds", "position.exit_age_seconds", "entry_order.status",
            "entry_order.age_seconds", "entry_order.requested_price", "entry_order.filled_shares",
            "entry_order.remaining_shares", "entry_order.last_rejection_reason", "exit_order.status",
            "exit_order.age_seconds", "exit_order.requested_price", "exit_order.filled_shares",
            "exit_order.remaining_shares", "exit_order.last_rejection_reason"
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
