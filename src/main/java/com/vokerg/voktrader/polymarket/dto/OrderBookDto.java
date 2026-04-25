package com.vokerg.voktrader.polymarket.dto;

import java.util.List;

public record OrderBookDto(
        String tokenId,
        List<PriceLevelDto> bids,
        List<PriceLevelDto> asks
) {
}
