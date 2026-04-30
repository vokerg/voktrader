package com.vokerg.voktrader.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClobMarketInfoDto(
        @JsonProperty("tbf")
        BigDecimal takerBaseFee,

        @JsonProperty("fd")
        FeeDetails feeDetails
) {

    public BigDecimal platformFeeRate() {
        if (feeDetails != null && feeDetails.rate != null) {
            return feeDetails.rate;
        }

        if (takerBaseFee == null) {
            return BigDecimal.ZERO;
        }

        return takerBaseFee.movePointLeft(4);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FeeDetails(
            @JsonProperty("r")
            BigDecimal rate
    ) {
    }
}
