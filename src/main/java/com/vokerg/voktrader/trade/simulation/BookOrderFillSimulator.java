package com.vokerg.voktrader.trade.simulation;

import com.vokerg.voktrader.backtest.BacktestFillModel;
import com.vokerg.voktrader.marketdata.FillEstimate;
import com.vokerg.voktrader.marketdata.OrderBookLevel;
import com.vokerg.voktrader.marketdata.OutcomeOrderBook;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class BookOrderFillSimulator {
    private static final int SCALE = 8;

    public FillEstimate immediateFill(TradeIntent intent, OutcomeOrderBook book) {
        if (book == null) {
            return null;
        }
        OutcomeOrderBook executableBook = limitBook(intent, book);
        if (intent.side() == TradeSide.BUY) {
            return executableBook.estimateBuyUsd(intent.amountUsd());
        }
        return executableBook.estimateSellShares(intent.shares());
    }

    public MakerFillEstimate makerFillEstimate(
            TradeOrderEntity order,
            OutcomeOrderBook book,
            BacktestFillModel fillModel,
            BigDecimal makerTouchFillRatio
    ) {
        if (book == null) {
            return null;
        }
        BigDecimal limit = order.getRequestedPrice();
        if (limit == null) {
            return null;
        }
        BigDecimal remainingShares = remainingShares(order);
        if (remainingShares.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (order.getSide() == TradeSide.BUY) {
            OrderBookLevel bestAsk = book.bestAsk().orElse(null);
            if (bestAsk == null) {
                return null;
            }
            int comparison = bestAsk.price().compareTo(limit);
            if (comparison < 0) {
                return new MakerFillEstimate(remainingShares, limit, true, "ask crossed below buy limit");
            }
            if (comparison == 0 && fillModel == BacktestFillModel.MAKER_TOUCH) {
                return touchPartial(remainingShares, bestAsk.size(), limit, makerTouchFillRatio, "ask touched buy limit");
            }
            return null;
        }
        OrderBookLevel bestBid = book.bestBid().orElse(null);
        if (bestBid == null) {
            return null;
        }
        int comparison = bestBid.price().compareTo(limit);
        if (comparison > 0) {
            return new MakerFillEstimate(remainingShares, limit, true, "bid crossed above sell limit");
        }
        if (comparison == 0 && fillModel == BacktestFillModel.MAKER_TOUCH) {
            return touchPartial(remainingShares, bestBid.size(), limit, makerTouchFillRatio, "bid touched sell limit");
        }
        return null;
    }

    public BigDecimal requestedShares(TradeOrderEntity order) {
        if (order.getRequestedShares() != null) {
            return order.getRequestedShares();
        }
        if (order.getRequestedAmountUsd() != null && order.getRequestedPrice() != null && order.getRequestedPrice().compareTo(BigDecimal.ZERO) > 0) {
            return order.getRequestedAmountUsd().divide(order.getRequestedPrice(), SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal remainingShares(TradeOrderEntity order) {
        if (order.getRemainingShares() != null) {
            return order.getRemainingShares();
        }
        BigDecimal requested = requestedShares(order);
        BigDecimal remaining = requested.subtract(nullToZero(order.getFilledShares()));
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private OutcomeOrderBook limitBook(TradeIntent intent, OutcomeOrderBook book) {
        BigDecimal limit = intent.expectedPrice();
        if (limit == null) {
            return book;
        }
        if (intent.side() == TradeSide.BUY) {
            return new OutcomeOrderBook(
                    book.tokenId(),
                    book.outcome(),
                    book.bids(),
                    book.asks().stream().filter(level -> level.price().compareTo(limit) <= 0).toList(),
                    book.updatedAt()
            );
        }
        return new OutcomeOrderBook(
                book.tokenId(),
                book.outcome(),
                book.bids().stream().filter(level -> level.price().compareTo(limit) >= 0).toList(),
                book.asks(),
                book.updatedAt()
        );
    }

    private MakerFillEstimate touchPartial(
            BigDecimal remainingShares,
            BigDecimal visibleDepth,
            BigDecimal price,
            BigDecimal makerTouchFillRatio,
            String reason
    ) {
        BigDecimal candidate = nullToZero(visibleDepth).multiply(makerTouchFillRatio == null ? BigDecimal.ZERO : makerTouchFillRatio);
        BigDecimal shares = candidate.min(remainingShares).setScale(SCALE, RoundingMode.HALF_UP);
        if (shares.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return new MakerFillEstimate(shares, price, shares.compareTo(remainingShares) >= 0, reason);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record MakerFillEstimate(BigDecimal shares, BigDecimal price, boolean fullFill, String reason) {
    }
}
