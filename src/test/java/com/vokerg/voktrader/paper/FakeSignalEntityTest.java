package com.vokerg.voktrader.paper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FakeSignalEntityTest {

    private final PaperTradeFeeCalculator calculator = new PaperTradeFeeCalculator();

    @Test
    void winningTradePnlIsLowerAfterFee() {
        FakeSignalEntity withoutFee = signalWithFeeRate(BigDecimal.ZERO);
        FakeSignalEntity withFee = signalWithFeeRate(bd("0.02"));

        withoutFee.resolve("Yes", Instant.now());
        withFee.resolve("Yes", Instant.now());

        assertThat(withFee.getFakePnl()).isLessThan(withoutFee.getFakePnl());
    }

    @Test
    void losingTradeRemainsFakeSizeLoss() {
        FakeSignalEntity signal = signalWithFeeRate(bd("0.02"));

        signal.resolve("No", Instant.now());

        assertThat(signal.getFakePnl()).isEqualByComparingTo("-1.00");
    }

    private FakeSignalEntity signalWithFeeRate(BigDecimal feeRate) {
        PaperTradeFeeCalculator.EntryFees fees = calculator.calculateEntry(
                bd("1.00"),
                bd("0.50"),
                feeRate
        );

        return FakeSignalEntity.openBuySignal(
                "market-id",
                "market-slug",
                "Question?",
                "Yes",
                "token-id",
                bd("0.50"),
                bd("1.00"),
                fees.grossFakeShares(),
                fees.feeRate(),
                fees.entryFeeUsd(),
                fees.netFakeShares(),
                "rule",
                "reason",
                Instant.now(),
                Instant.now().plusSeconds(60)
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
