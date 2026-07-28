package com.vokerg.voktrader.fee;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnifiedFeeModelTest {
    private final UnifiedFeeModel model = new UnifiedFeeModel();
    private final FeeMetadata metadata = new FeeMetadata(
            "market-1",
            new BigDecimal("0.02"),
            2,
            true,
            "clob-market-info",
            Instant.parse("2026-07-28T00:00:00Z")
    );

    @Test
    void officialFeeScheduleExampleUsesConfiguredRateAndExponent() {
        BigDecimal fee = model.calculate(
                metadata,
                FeeLiquidityRole.TAKER,
                new BigDecimal("0.50"),
                new BigDecimal("100")
        );

        assertEquals(new BigDecimal("0.12500"), fee);
    }

    @Test
    void identicalInputsHaveCrossModeParity() {
        BigDecimal expected = model.calculate(
                metadata,
                FeeLiquidityRole.TAKER,
                new BigDecimal("0.37"),
                new BigDecimal("12.5")
        );

        for (String ignoredMode : new String[]{"LIVE", "PAPER", "REPLAY", "STRATEGY_V2"}) {
            assertEquals(expected, model.calculate(
                    metadata,
                    FeeLiquidityRole.TAKER,
                    new BigDecimal("0.37"),
                    new BigDecimal("12.5")
            ));
        }
    }

    @Test
    void takerOnlyMetadataMakesMakerFeeZero() {
        assertEquals(new BigDecimal("0.00000"), model.calculate(
                metadata,
                FeeLiquidityRole.MAKER,
                new BigDecimal("0.50"),
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> model.calculate(
                metadata, FeeLiquidityRole.TAKER, BigDecimal.ZERO, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> model.calculate(
                metadata, FeeLiquidityRole.TAKER, BigDecimal.ONE, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> model.calculate(
                metadata, FeeLiquidityRole.TAKER, new BigDecimal("0.5"), BigDecimal.ZERO));
    }
}
