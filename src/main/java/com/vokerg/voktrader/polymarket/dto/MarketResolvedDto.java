package com.vokerg.voktrader.polymarket.dto;

import com.vokerg.voktrader.paper.Side;

public record MarketResolvedDto(
        String marketId,
        Side winningSide
) {
}
