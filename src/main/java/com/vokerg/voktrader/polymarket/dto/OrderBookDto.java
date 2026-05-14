package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderBookDto(
        String market,

        @JsonProperty("asset_id")
        String assetId,

        String timestamp,
        String hash,

        List<PriceLevelDto> bids,
        List<PriceLevelDto> asks,

        @JsonProperty("min_order_size")
        String minOrderSize,

        @JsonProperty("tick_size")
        String tickSize,

        @JsonProperty("neg_risk")
        Boolean negRisk,

        @JsonProperty("last_trade_price")
        String lastTradePrice
) {
    public Optional<BigDecimal> bestBid() {
        if (bids == null || bids.isEmpty()) {
            return Optional.empty();
        }

        return bids.stream()
                .map(PriceLevelDto::priceDecimal)
                .max(Comparator.naturalOrder());
    }

    public Optional<BigDecimal> bestAsk() {
        if (asks == null || asks.isEmpty()) {
            return Optional.empty();
        }

        return asks.stream()
                .map(PriceLevelDto::priceDecimal)
                .min(Comparator.naturalOrder());
    }

    public Optional<BigDecimal> spread() {
        Optional<BigDecimal> bid = bestBid();
        Optional<BigDecimal> ask = bestAsk();

        if (bid.isEmpty() || ask.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(ask.get().subtract(bid.get()));
    }
}
