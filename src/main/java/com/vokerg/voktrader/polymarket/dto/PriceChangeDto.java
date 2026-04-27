package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceChangeDto(
        @JsonProperty("asset_id")
        String assetId,

        String price,
        String size,
        String side,
        String hash,

        @JsonProperty("best_bid")
        String bestBid,

        @JsonProperty("best_ask")
        String bestAsk
) {
}