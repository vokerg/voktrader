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

        assertThat(withFee.getPaperPnl()).isLessThan(withoutFee.getPaperPnl());
    }

    @Test
    void losingTradeRemainsPaperSizeLoss() {
        SignalEntity signal = signalWithFeeRate(bd("0.02"));

        signal.resolve("No", Instant.now());

        assertThat(signal.getPaperPnl()).isEqualByComparingTo("-1.00");
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
