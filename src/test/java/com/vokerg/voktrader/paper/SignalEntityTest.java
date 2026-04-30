package com.vokerg.voktrader.paper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEntityTest {

    private final PaperTradeFeeCalculator calculator = new PaperTradeFeeCalculator();

    @Test
    void winningTradePnlIsLowerAfterFee() {
        SignalEntity withoutFee = signalWithFeeRate(BigDecimal.ZERO);
        SignalEntity withFee = signalWithFeeRate(bd("0.02"));

        withoutFee.resolve("Yes", Instant.now());
        withFee.resolve("Yes", Instant.now());

        assertThat(withFee.getPnlUsd()).isLessThan(withoutFee.getPnlUsd());
    }

    @Test
    void losingTradeRemainsPaperSizeLoss() {
        SignalEntity signal = signalWithFeeRate(bd("0.02"));

        signal.resolve("No", Instant.now());

        assertThat(signal.getPnlUsd()).isEqualByComparingTo("-1.00");
    }

    @Test
    void sellingTradeSubtractsExitFeeFromPnl() {
        SignalEntity signal = signalWithFeeRate(bd("0.02"));

        signal.sell(bd("0.60"), bd("0.01"), "test", Instant.now());

        assertThat(signal.getExitFeeUsd()).isEqualByComparingTo("0.01");
        assertThat(signal.getTotalFeeUsd()).isGreaterThan(signal.getEntryFeeUsd());
        assertThat(signal.getPnlUsd()).isEqualByComparingTo("0.1780000000");
    }

    private SignalEntity signalWithFeeRate(BigDecimal feeRate) {
        PaperTradeFeeCalculator.EntryFees fees = calculator.calculateEntry(
                bd("1.00"),
                bd("0.50"),
                feeRate
        );

        return SignalEntity.openPaperBuySignal(
                "market-id",
                "market-slug",
                "Question?",
                "Yes",
                "token-id",
                bd("0.50"),
                bd("1.00"),
                fees.grossPaperShares(),
                fees.feeRate(),
                fees.entryFeeUsd(),
                fees.netPaperShares(),
                bd("0.49"),
                bd("0.50"),
                bd("0.01"),
                Instant.now(),
                100L,
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
