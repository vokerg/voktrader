package com.vokerg.voktrader.trade;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PaperFeeCalculator extends PolymarketFeeCalculator {
    public BigDecimal estimate(BigDecimal shares, BigDecimal price, BigDecimal feeRate) {
        return estimateTakerFeeUsd(shares, price, feeRate);
    }
}
