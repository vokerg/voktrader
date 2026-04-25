package com.vokerg.voktrader.polymarket.dto;

import java.math.BigDecimal;

public record PriceLevelDto(
        BigDecimal price,
        BigDecimal size
) {
}
