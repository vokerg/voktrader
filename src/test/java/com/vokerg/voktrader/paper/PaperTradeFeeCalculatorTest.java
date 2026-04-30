package com.vokerg.voktrader.paper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaperTradeFeeCalculatorTest {

    private final PaperTradeFeeCalculator calculator = new PaperTradeFeeCalculator();

    @Test
    void noFeeWhenFeeRateIsZero() {
        PaperTradeFeeCalculator.EntryFees fees = calculator.calculateEntry(
                bd("1.00"),
                bd("0.50"),
                BigDecimal.ZERO
        );

        assertThat(fees.entryFeeUsd()).isEqualByComparingTo("0.00000000");
        assertThat(fees.grossPaperShares()).isEqualByComparingTo("2.00000000");
        assertThat(fees.netPaperShares()).isEqualByComparingTo("2.00000000");
    }

    @Test
    void feeIsHighestNearFiftyCents() {
        BigDecimal feeRate = bd("0.02");

        BigDecimal nearFive = calculator.calculateFee(bd("100"), bd("0.05"), feeRate);
        BigDecimal nearFifty = calculator.calculateFee(bd("100"), bd("0.50"), feeRate);
        BigDecimal nearNinetyFive = calculator.calculateFee(bd("100"), bd("0.95"), feeRate);

        assertThat(nearFifty).isGreaterThan(nearFive);
        assertThat(nearFifty).isGreaterThan(nearNinetyFive);
    }

    @Test
    void feeIsLowerNearPriceExtremes() {
        BigDecimal feeRate = bd("0.02");

        BigDecimal nearFive = calculator.calculateFee(bd("100"), bd("0.05"), feeRate);
        BigDecimal nearFifty = calculator.calculateFee(bd("100"), bd("0.50"), feeRate);
        BigDecimal nearNinetyFive = calculator.calculateFee(bd("100"), bd("0.95"), feeRate);

        assertThat(nearFive).isLessThan(nearFifty);
        assertThat(nearNinetyFive).isLessThan(nearFifty);
    }

    @Test
    void cryptoFeeMatchesPolymarketDocsAtTwentyFiveCents() {
        BigDecimal fee = calculator.calculateFee(
                bd("100"),
                bd("0.25"),
                bd("0.072")
        );

        assertThat(fee).isEqualByComparingTo("1.35000000");
    }

    @Test
    void cryptoFeeMatchesPolymarketDocsAtFiftyCents() {
        BigDecimal fee = calculator.calculateFee(
                bd("100"),
                bd("0.50"),
                bd("0.072")
        );

        assertThat(fee).isEqualByComparingTo("1.80000000");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
