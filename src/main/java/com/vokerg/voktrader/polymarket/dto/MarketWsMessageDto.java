package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketWsMessageDto(
        @JsonProperty("event_type") String eventType,

        @JsonProperty("asset_id") String assetId,

        String market,

        List<PriceLevelDto> bids,
        List<PriceLevelDto> asks,

        @JsonProperty("price_changes") List<PriceChangeDto> priceChanges,

        @JsonProperty("best_bid") String bestBid,

        @JsonProperty("best_ask") String bestAsk,

        String spread,
        String timestamp,
        String hash,

        String id,
        String question,
        String slug,
        String description,

        @JsonProperty("assets_ids") List<String> assetIds,

        List<String> outcomes,

        @JsonProperty("winning_asset_id") String winningAssetId,

        @JsonProperty("winning_outcome") String winningOutcome) {
    public boolean isBook() {
        return "book".equals(eventType);
    }

    public boolean isPriceChange() {
        return "price_change".equals(eventType);
    }

    public boolean isBestBidAsk() {
        return "best_bid_ask".equals(eventType);
    }

    public boolean isMarketResolved() {
        return "market_resolved".equals(eventType);
    }

    public Optional<BigDecimal> effectiveBestBid() {
        Optional<BigDecimal> directBestBid = parseDecimal(bestBid);
        if (directBestBid.isPresent()) {
            return directBestBid;
        }

        if (priceChanges != null) {
            Optional<BigDecimal> fromPriceChange = priceChanges.stream()
                    .map(PriceChangeDto::bestBid)
                    .map(this::parseDecimal)
                    .flatMap(Optional::stream)
                    .findFirst();

            if (fromPriceChange.isPresent()) {
                return fromPriceChange;
            }
        }

        if (bids == null || bids.isEmpty()) {
            return Optional.empty();
        }

        return bids.stream()
                .map(PriceLevelDto::priceDecimal)
                .max(Comparator.naturalOrder());
    }

    public Optional<BigDecimal> effectiveBestAsk() {
        Optional<BigDecimal> directBestAsk = parseDecimal(bestAsk);
        if (directBestAsk.isPresent()) {
            return directBestAsk;
        }

        if (priceChanges != null) {
            Optional<BigDecimal> fromPriceChange = priceChanges.stream()
                    .map(PriceChangeDto::bestAsk)
                    .map(this::parseDecimal)
                    .flatMap(Optional::stream)
                    .findFirst();

            if (fromPriceChange.isPresent()) {
                return fromPriceChange;
            }
        }

        if (asks == null || asks.isEmpty()) {
            return Optional.empty();
        }

        return asks.stream()
                .map(PriceLevelDto::priceDecimal)
                .min(Comparator.naturalOrder());
    }

    public Optional<BigDecimal> effectiveSpread() {
        Optional<BigDecimal> bid = effectiveBestBid();
        Optional<BigDecimal> ask = effectiveBestAsk();

        if (bid.isEmpty() || ask.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(ask.get().subtract(bid.get()));
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
}
