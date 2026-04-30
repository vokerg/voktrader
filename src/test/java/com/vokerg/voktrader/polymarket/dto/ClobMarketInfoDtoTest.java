package com.vokerg.voktrader.polymarket.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ClobMarketInfoDtoTest {

    @Test
    void platformFeeRateUsesFeeDetailsRate() {
        ClobMarketInfoDto info = new ClobMarketInfoDto(
                bd("100"),
                new ClobMarketInfoDto.FeeDetails(bd("0.02"))
        );

        assertThat(info.platformFeeRate()).isEqualByComparingTo("0.02");
    }

    @Test
    void platformFeeRateFallsBackToTakerBaseFeeBps() {
        ClobMarketInfoDto info = new ClobMarketInfoDto(
                bd("100"),
                null
        );

        assertThat(info.platformFeeRate()).isEqualByComparingTo("0.01");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
