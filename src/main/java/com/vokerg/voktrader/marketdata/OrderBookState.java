package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class OrderBookState {
    private final Map<String, OutcomeOrderBook> booksByTokenId = new ConcurrentHashMap<>();

    public void update(
            String tokenId,
            String outcome,
            List<PriceLevelDto> bids,
            List<PriceLevelDto> asks,
            Instant updatedAt
    ) {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().update(tokenId, outcome, bids, asks, updatedAt);
            return;
        }
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        booksByTokenId.put(
                tokenId,
                new OutcomeOrderBook(
                        tokenId,
                        outcome,
                        levels(bids),
                        levels(asks),
                        updatedAt == null ? Instant.now() : updatedAt
                )
        );
    }

    public void applyPriceChange(String tokenId, String outcome, String side, String price, String size, Instant updatedAt) {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().applyPriceChange(tokenId, outcome, side, price, size, updatedAt);
            return;
        }
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        Optional<OrderBookSide> orderBookSide = parseSide(side);
        Optional<BigDecimal> parsedPrice = parseDecimal(price);
        Optional<BigDecimal> parsedSize = parseDecimal(size);
        if (orderBookSide.isEmpty() || parsedPrice.isEmpty() || parsedSize.isEmpty()) {
            return;
        }

        booksByTokenId.compute(tokenId, (ignored, current) -> {
            OutcomeOrderBook book = current == null
                    ? new OutcomeOrderBook(tokenId, outcome, List.of(), List.of(), updatedAt)
                    : current;
            return book.withPriceLevel(orderBookSide.get(), parsedPrice.get(), parsedSize.get(), updatedAt);
        });
    }

    public Optional<OutcomeOrderBook> byTokenId(String tokenId) {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().byTokenId(tokenId);
        }
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(booksByTokenId.get(tokenId));
    }

    public Optional<OutcomeOrderBook> byOutcome(String outcome) {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().byOutcome(outcome);
        }
        if (outcome == null || outcome.isBlank()) {
            return Optional.empty();
        }
        return booksByTokenId.values().stream()
                .filter(book -> outcome.equalsIgnoreCase(book.outcome()))
                .findFirst();
    }

    public Map<String, OutcomeOrderBook> allByTokenId() {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().allByTokenId();
        }
        return Map.copyOf(booksByTokenId);
    }

    public Map<String, OutcomeOrderBook> allByOutcome() {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            return delegate.get().allByOutcome();
        }
        Map<String, OutcomeOrderBook> books = new LinkedHashMap<>();
        booksByTokenId.values().forEach(book -> {
            if (book.outcome() != null) {
                books.put(book.outcome(), book);
            }
        });
        return Map.copyOf(books);
    }

    public Optional<FillEstimate> estimateBuyUsd(String tokenId, BigDecimal amountUsd) {
        return byTokenId(tokenId).map(book -> book.estimateBuyUsd(amountUsd));
    }

    public Optional<FillEstimate> estimateSellShares(String tokenId, BigDecimal shares) {
        return byTokenId(tokenId).map(book -> book.estimateSellShares(shares));
    }

    public void clear() {
        Optional<OrderBookState> delegate = delegate();
        if (delegate.isPresent()) {
            delegate.get().clear();
            return;
        }
        booksByTokenId.clear();
    }

    public static Optional<OrderBookState> current() {
        return BotRuntimeContextHolder.currentOrderBookState();
    }

    private List<OrderBookLevel> levels(List<PriceLevelDto> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.stream()
                .map(this::level)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<OrderBookLevel> level(PriceLevelDto level) {
        if (level == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> price = parseDecimal(level.price());
        Optional<BigDecimal> size = parseDecimal(level.size());
        if (price.isEmpty() || size.isEmpty()) {
            return Optional.empty();
        }
        OrderBookLevel orderBookLevel = new OrderBookLevel(price.get(), size.get());
        return orderBookLevel.usable() ? Optional.of(orderBookLevel) : Optional.empty();
    }

    private Optional<BigDecimal> parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<OrderBookSide> parseSide(String side) {
        if (side == null || side.isBlank()) {
            return Optional.empty();
        }
        return switch (side.toUpperCase()) {
            case "BUY" -> Optional.of(OrderBookSide.BUY);
            case "SELL" -> Optional.of(OrderBookSide.SELL);
            default -> Optional.empty();
        };
    }

    private Optional<OrderBookState> delegate() {
        return BotRuntimeContextHolder.currentOrderBookState()
                .filter(state -> state != this);
    }
}
