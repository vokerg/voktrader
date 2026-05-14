package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceLevelDto(
        String price,
        String size
) {
    public BigDecimal priceDecimal() {
        return new BigDecimal(price);
    }

    public BigDecimal sizeDecimal() {
        return new BigDecimal(size);
    }
}
