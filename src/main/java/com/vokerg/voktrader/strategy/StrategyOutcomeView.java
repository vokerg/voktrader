package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.FeeEstimate;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.marketdata.OutcomePrice;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Strategy-facing view of one tradable outcome.
 *
 * <p>The important distinction is price versus executable liquidity:</p>
 * <ul>
 *     <li>{@link #price()} is the latest top-of-book snapshot: best bid, best ask, spread, update time.</li>
 *     <li>{@link #orderBook()} is the current in-memory depth, when available.</li>
 *     <li>{@link #estimateTakerBuy(BigDecimal)} walks asks and estimates whether a FOK/taker buy can fill.</li>
 *     <li>{@link #estimateTakerSell(BigDecimal)} walks bids and estimates whether a FOK/taker sell can fill.</li>
 *     <li>{@link #estimateTakerFee(FillEstimate)} estimates fee from the estimated filled shares and average price.</li>
 *     <li>{@link #estimateMakerBuyFee(BigDecimal)} estimates fee for posting at best bid; use as comparison only until maker execution exists.</li>
 * </ul>
 */
public interface StrategyOutcomeView {

    /** Outcome label, normally "Up" or "Down". */
    String outcome();

    /** Polymarket CLOB token id for this outcome. */
    String tokenId();

    /** Latest top-of-book price state. This must be complete before normal strategy entry helpers call evaluators. */
    OutcomePrice price();

    /** Current full-depth book, if the feed has received REST seed or websocket book data for this token. */
    Optional<OutcomeOrderBook> orderBook();

    /** Midpoint between latest best bid and best ask. */
    BigDecimal mid();

    /** Latest spread from the top-of-book price state. */
    BigDecimal spread();

    /** Age of top-of-book price data in milliseconds. */
    Optional<Long> priceAgeMs();

    /** Age of full-depth order book data in milliseconds. Empty when no book exists. */
    Optional<Long> bookAgeMs();

    /** Total visible bid-side shares in the current book. Returns zero when no book exists. */
    BigDecimal bidDepth();

    /** Total visible ask-side shares in the current book. Returns zero when no book exists. */
    BigDecimal askDepth();

    /** Visible bid shares within {@code priceRange} of best bid. */
    BigDecimal bidDepthWithin(BigDecimal priceRange);

    /** Visible ask shares within {@code priceRange} of best ask. */
    BigDecimal askDepthWithin(BigDecimal priceRange);

    /** Best bid level from the full book, if present. */
    Optional<OrderBookLevel> bestBidLevel();

    /** Best ask level from the full book, if present. */
    Optional<OrderBookLevel> bestAskLevel();

    /** Taker buy estimate for spending the supplied USD by walking ask levels from best ask upward. */
    Optional<FillEstimate> estimateTakerBuy(BigDecimal amountUsd);

    /** Taker sell estimate for selling the supplied shares by walking bid levels from best bid downward. */
    Optional<FillEstimate> estimateTakerSell(BigDecimal shares);

    /** Taker fee estimate for a fill estimate produced by this view. */
    Optional<FeeEstimate> estimateTakerFee(FillEstimate estimate);

    /** Maker fee estimate for buying at the current best bid. Comparison only until maker execution is implemented. */
    Optional<FeeEstimate> estimateMakerBuyFee(BigDecimal amountUsd);
}
