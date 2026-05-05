package com.vokerg.voktrader.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record OutcomeOrderBook(
        String tokenId,
        String outcome,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks,
        Instant updatedAt
) {
    private static final int PRICE_SCALE = 8;

    public OutcomeOrderBook {
        bids = sortedBids(bids);
        asks = sortedAsks(asks);
    }

    public Optional<OrderBookLevel> bestBid() {
        return bids.stream().findFirst();
    }

    public Optional<OrderBookLevel> bestAsk() {
        return asks.stream().findFirst();
    }

    public Optional<BigDecimal> spread() {
        Optional<OrderBookLevel> bid = bestBid();
        Optional<OrderBookLevel> ask = bestAsk();
        if (bid.isEmpty() || ask.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ask.get().price().subtract(bid.get().price()));
    }

    public BigDecimal bidDepth() {
        return totalSize(bids);
    }

    public BigDecimal askDepth() {
        return totalSize(asks);
    }

    public BigDecimal bidDepthWithin(BigDecimal priceRange) {
        Optional<OrderBookLevel> bestBid = bestBid();
        if (bestBid.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal floor = bestBid.get().price().subtract(nonNegative(priceRange));
        return bids.stream()
                .filter(level -> level.price().compareTo(floor) >= 0)
                .map(OrderBookLevel::size)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal askDepthWithin(BigDecimal priceRange) {
        Optional<OrderBookLevel> bestAsk = bestAsk();
        if (bestAsk.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal ceiling = bestAsk.get().price().add(nonNegative(priceRange));
        return asks.stream()
                .filter(level -> level.price().compareTo(ceiling) <= 0)
                .map(OrderBookLevel::size)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public OutcomeOrderBook withPriceLevel(OrderBookSide side, BigDecimal price, BigDecimal size, Instant updatedAt) {
        if (side == null || price == null || price.compareTo(BigDecimal.ZERO) <= 0 || size == null) {
            return this;
        }
        return switch (side) {
            case BUY -> new OutcomeOrderBook(tokenId, outcome, replaceLevel(bids, price, size), asks, updatedAt);
            case SELL -> new OutcomeOrderBook(tokenId, outcome, bids, replaceLevel(asks, price, size), updatedAt);
        };
    }

    public FillEstimate estimateBuyUsd(BigDecimal amountUsd) {
        if (amountUsd == null || amountUsd.compareTo(BigDecimal.ZERO) <= 0) {
            return emptyEstimate(OrderBookSide.BUY, amountUsd, null);
        }

        BigDecimal remainingUsd = amountUsd;
        BigDecimal filledShares = BigDecimal.ZERO;
        BigDecimal notionalUsd = BigDecimal.ZERO;
        BigDecimal worstPrice = null;
        int levelsConsumed = 0;

        for (OrderBookLevel level : asks) {
            BigDecimal levelCost = level.price().multiply(level.size());
            levelsConsumed++;
            worstPrice = level.price();
            if (remainingUsd.compareTo(levelCost) >= 0) {
                filledShares = filledShares.add(level.size());
                notionalUsd = notionalUsd.add(levelCost);
                remainingUsd = remainingUsd.subtract(levelCost);
                continue;
            }

            BigDecimal partialShares = remainingUsd.divide(level.price(), PRICE_SCALE, RoundingMode.HALF_UP);
            filledShares = filledShares.add(partialShares);
            notionalUsd = notionalUsd.add(remainingUsd);
            remainingUsd = BigDecimal.ZERO;
            break;
        }

        boolean complete = remainingUsd.compareTo(BigDecimal.ZERO) <= 0;
        return new FillEstimate(
                tokenId,
                outcome,
                OrderBookSide.BUY,
                amountUsd,
                null,
                filledShares,
                notionalUsd,
                averagePrice(notionalUsd, filledShares),
                worstPrice,
                complete,
                levelsConsumed,
                updatedAt
        );
    }

    public FillEstimate estimateSellShares(BigDecimal shares) {
        if (shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            return emptyEstimate(OrderBookSide.SELL, null, shares);
        }

        BigDecimal remainingShares = shares;
        BigDecimal filledShares = BigDecimal.ZERO;
        BigDecimal notionalUsd = BigDecimal.ZERO;
        BigDecimal worstPrice = null;
        int levelsConsumed = 0;

        for (OrderBookLevel level : bids) {
            levelsConsumed++;
            worstPrice = level.price();
            if (remainingShares.compareTo(level.size()) >= 0) {
                filledShares = filledShares.add(level.size());
                notionalUsd = notionalUsd.add(level.price().multiply(level.size()));
                remainingShares = remainingShares.subtract(level.size());
                continue;
            }

            filledShares = filledShares.add(remainingShares);
            notionalUsd = notionalUsd.add(level.price().multiply(remainingShares));
            remainingShares = BigDecimal.ZERO;
            break;
        }

        boolean complete = remainingShares.compareTo(BigDecimal.ZERO) <= 0;
        return new FillEstimate(
                tokenId,
                outcome,
                OrderBookSide.SELL,
                null,
                shares,
                filledShares,
                notionalUsd,
                averagePrice(notionalUsd, filledShares),
                worstPrice,
                complete,
                levelsConsumed,
                updatedAt
        );
    }

    private FillEstimate emptyEstimate(OrderBookSide side, BigDecimal requestedUsd, BigDecimal requestedShares) {
        return new FillEstimate(
                tokenId,
                outcome,
                side,
                requestedUsd,
                requestedShares,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                false,
                0,
                updatedAt
        );
    }

    private static BigDecimal averagePrice(BigDecimal notionalUsd, BigDecimal shares) {
        if (shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return notionalUsd.divide(shares, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static List<OrderBookLevel> sortedBids(List<OrderBookLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.stream()
                .filter(OrderBookLevel::usable)
                .sorted(Comparator.comparing(OrderBookLevel::price).reversed())
                .toList();
    }

    private static List<OrderBookLevel> sortedAsks(List<OrderBookLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.stream()
                .filter(OrderBookLevel::usable)
                .sorted(Comparator.comparing(OrderBookLevel::price))
                .toList();
    }

    private static BigDecimal totalSize(List<OrderBookLevel> levels) {
        return levels.stream()
                .map(OrderBookLevel::size)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private static List<OrderBookLevel> replaceLevel(List<OrderBookLevel> levels, BigDecimal price, BigDecimal size) {
        List<OrderBookLevel> withoutPrice = levels.stream()
                .filter(level -> level.price().compareTo(price) != 0)
                .toList();
        if (size.compareTo(BigDecimal.ZERO) <= 0) {
            return withoutPrice;
        }
        return append(withoutPrice, new OrderBookLevel(price, size));
    }

    private static List<OrderBookLevel> append(List<OrderBookLevel> levels, OrderBookLevel level) {
        java.util.ArrayList<OrderBookLevel> updated = new java.util.ArrayList<>(levels);
        updated.add(level);
        return updated;
    }
}
