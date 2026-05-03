package com.vokerg.voktrader.economy;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EconomyServiceTest {

    private final TradingProperties properties = new TradingProperties();
    private final EconomyService economyService = new EconomyService(properties, new PolymarketFeeCalculator());

    @Test
    void estimatesExitEconomyWithTakerFeesAndMinimumProfit() {
        TradeEntity trade = openTrade();

        ExitEconomy economy = economyService.estimateExit(
                trade,
                new BigDecimal("0.60"),
                new BigDecimal("0.10"),
                LiquidityRole.TAKER
        );

        assertThat(economy.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
        assertThat(economy.estimatedExitFeeUsd()).isEqualByComparingTo("0.03456000");
        assertThat(economy.estimatedTotalFeeUsd()).isEqualByComparingTo("0.03456000");
        assertThat(economy.estimatedNetPnlUsd()).isEqualByComparingTo("0.16544000");
        assertThat(economy.minimumProfitReached()).isTrue();
    }

    @Test
    void makerRoleUsesMakerFeeRate() {
        properties.setMakerFeeRate(new BigDecimal("0.01"));

        FeeEstimate fee = economyService.estimateFee(
                new BigDecimal("2.00"),
                new BigDecimal("0.60"),
                LiquidityRole.MAKER
        );

        assertThat(fee.feeRate()).isEqualByComparingTo("0.01");
        assertThat(fee.feeUsd()).isEqualByComparingTo("0.00480000");
    }

    private TradeEntity openTrade() {
        TradeEntity trade = TradeEntity.fromIntent(TradeIntent.buy(
                market(),
                price("up", "Up", "0.49", "0.50"),
                new BigDecimal("1.00"),
                "cost-aware-momentum-paper",
                "cost-aware-momentum",
                "test"
        ), com.vokerg.voktrader.trade.ExecutionMode.PAPER);
        trade.markOpen(
                new BigDecimal("0.50"),
                new BigDecimal("2.00"),
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                Instant.now()
        );
        return trade;
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "slug",
                "question",
                null,
                Instant.now().plusSeconds(120),
                true,
                false,
                true,
                true,
                null,
                null,
                null,
                null
        );
    }

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(
                tokenId,
                outcome,
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                Instant.now()
        );
    }
}
