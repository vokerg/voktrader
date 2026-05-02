package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PolymarketFeeCalculatorTest {
    private final PolymarketFeeCalculator calculator = new PolymarketFeeCalculator();

    @Test
    void estimatesCryptoTakerFees() {
        BigDecimal feeRate = new BigDecimal("0.072");

        assertThat(calculator.estimateTakerFeeUsd(
                new BigDecimal("1.724135"),
                new BigDecimal("0.58"),
                feeRate
        )).isEqualByComparingTo("0.03023995");

        assertThat(calculator.estimateTakerFeeUsd(
                new BigDecimal("1.72"),
                new BigDecimal("0.53"),
                feeRate
        )).isEqualByComparingTo("0.03084854");

        assertThat(calculator.estimateTakerFeeUsd(
                new BigDecimal("1.666665"),
                new BigDecimal("0.60"),
                feeRate
        )).isEqualByComparingTo("0.02879997");
    }

    @Test
    void invalidInputsReturnScaledZero() {
        assertThat(calculator.estimateTakerFeeUsd(null, new BigDecimal("0.60"), new BigDecimal("0.072")))
                .isEqualByComparingTo("0.00000000");
        assertThat(calculator.estimateTakerFeeUsd(new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("0.072")))
                .isEqualByComparingTo("0.00000000");
        assertThat(calculator.estimateTakerFeeUsd(new BigDecimal("1"), BigDecimal.ONE, new BigDecimal("0.072")))
                .isEqualByComparingTo("0.00000000");
    }
}
