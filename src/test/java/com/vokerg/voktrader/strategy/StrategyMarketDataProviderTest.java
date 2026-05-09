package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.economy.TradeEconomy;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyMarketDataProviderTest {
    private final TrackedMarketState trackedMarketState = new TrackedMarketState();
    private final LatestPriceState latestPriceState = new LatestPriceState();
    private final StrategyMarketDataProvider provider = new StrategyMarketDataProvider(
            trackedMarketState,
            latestPriceState,
            org.mockito.Mockito.mock(TradeEconomy.class)
    );

    @Test
    void outcomeMidIsNullWhenEitherSideMissing() {
        trackedMarketState.startTracking(market());
        latestPriceState.update("up-token", "Up", null, new BigDecimal("0.51"), Instant.parse("2026-05-09T12:00:00Z"));
        latestPriceState.update("down-token", "Down", new BigDecimal("0.49"), new BigDecimal("0.51"), Instant.parse("2026-05-09T12:00:00Z"));

        StrategyMarketView view = provider.currentUpDownMarket().orElseThrow();

        assertThat(view.up().mid()).isNull();
        assertThat(view.down().mid()).isEqualByComparingTo("0.50000000");
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "Question",
                "condition-id",
                "slug",
                Instant.parse("2026-05-09T12:05:00Z"),
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
}
