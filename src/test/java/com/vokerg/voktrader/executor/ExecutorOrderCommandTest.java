package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.TradeOrderType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorOrderCommandTest {
    @Test
    void preservesExplicitGtcWithoutPostOnly() {
        TradeIntent intent = TradeIntent.buy(
                null,
                market(),
                price(),
                new BigDecimal("1.00"),
                TradeOrderType.GTC,
                false,
                new BigDecimal("0.51"),
                "strategy",
                "rule",
                "entry"
        );

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, "key", true);

        assertThat(command.timeInForce()).isEqualTo("GTC");
        assertThat(command.postOnly()).isFalse();
    }

    @Test
    void makerHelperDefaultsToGtcPostOnly() {
        TradeIntent intent = TradeIntent.buyMaker(
                null,
                market(),
                price(),
                new BigDecimal("1.00"),
                "strategy",
                "rule",
                "entry"
        );

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, "key", true);

        assertThat(command.timeInForce()).isEqualTo("GTC");
        assertThat(command.postOnly()).isTrue();
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                Instant.parse("2026-04-30T10:05:00Z"),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
    }

    private OutcomePrice price() {
        return new OutcomePrice(
                "down",
                "Down",
                new BigDecimal("0.51"),
                new BigDecimal("0.52"),
                new BigDecimal("0.01"),
                Instant.parse("2026-04-30T10:00:01Z")
        );
    }
}
