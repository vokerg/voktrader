package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One complete read-only snapshot of the market data available to a strategy during a tick.
 *
 * <p>This is the main "building blocks" contract for strategy authors. New strategies should prefer this
 * view over directly reaching into {@code LatestPriceState}, {@code OrderBookState}, trading properties, or
 * fee services. The intent is to make strategy code small and explicit: choose an outcome, inspect price,
 * inspect book depth, compare taker/maker costs, then return a decision.</p>
 *
 * <p>Order style note: strategies can now express both immediate taker-style orders and resting maker-style
 * orders through {@link StrategyEntrySupport.EntrySignal}. FOK/FAK should be treated as executable-now taker
 * intents. GTC/GTD maker intents can rest on the book and may avoid taker fees, but live use requires order
 * reconciliation and cancellation policy because a resting order may remain unfilled.</p>
 */
public interface StrategyMarketView {

    /** Current tracked Polymarket market selected by the bot runtime. */
    GammaMarketDto market();

    /** Clock instant used for age, expiry, and decision calculations in this tick. */
    Instant now();

    /** Best available view for the Up outcome, including top-of-book, order book, fees, and fill estimates. */
    StrategyOutcomeView up();

    /** Best available view for the Down outcome, including top-of-book, order book, fees, and fill estimates. */
    StrategyOutcomeView down();

    /** All outcome views known to the standard up/down strategy helpers. */
    List<StrategyOutcomeView> outcomes();

    /** Find an outcome by display name, for example "Up" or "Down". */
    Optional<StrategyOutcomeView> outcome(String outcome);

    /** Find an outcome by token id. Useful when evaluating an already-open trade. */
    Optional<StrategyOutcomeView> token(String tokenId);

    /** Seconds until market expiry at the time this view was created. Empty when the market has no end date. */
    Optional<Long> secondsToExpiry();
}
