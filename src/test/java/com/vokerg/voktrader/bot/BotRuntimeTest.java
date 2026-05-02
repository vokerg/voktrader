package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.MarketPersistenceService;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.polymarket.dto.PriceChangeDto;
import com.vokerg.voktrader.resolution.MarketResolutionService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotRuntimeTest {

    @Test
    void rollsExpiredMarketAfterConfiguredGrace() {
        BotConfigEntity config = BotConfigEntity.create(
                "test-bot",
                MarketFamily.BTC_5M,
                "cost-aware-momentum-paper",
                true
        );
        GammaClient gammaClient = mock(GammaClient.class);
        MarketPersistenceService marketPersistenceService = mock(MarketPersistenceService.class);
        MarketSelectionProperties marketSelectionProperties = new MarketSelectionProperties("5m", 60L, 300L, 0L);
        BotRuntime runtime = new BotRuntime(
                config,
                gammaClient,
                mock(ClobClient.class),
                mock(PolymarketWebSocketClient.class),
                mock(ObjectMapper.class),
                marketSelectionProperties,
                marketPersistenceService,
                mock(MarketResolutionService.class)
        );
        GammaMarketDto expiredMarket = marketEndingAt(Instant.now().minusSeconds(1));
        when(gammaClient.getMarketBySlug(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        when(gammaClient.searchBitcoinUpDownMarkets()).thenReturn(Flux.empty());

        runtime.context().trackedMarketState().startTracking(expiredMarket);

        runtime.ensureMarketIsTracked();

        verify(marketPersistenceService).markStopped("expired-market");
    }

    @Test
    void priceChangeMessageUpdatesEachChangedToken() throws Exception {
        BotRuntime runtime = runtimeWithEmptyMarketLookup();
        runtime.context().trackedMarketState().startTracking(activeMarket());
        Method method = BotRuntime.class.getDeclaredMethod(
                "handleMarketMessage",
                MarketWsMessageDto.class,
                String.class,
                Map.class
        );
        method.setAccessible(true);
        MarketWsMessageDto message = new MarketWsMessageDto(
                "price_change",
                null,
                "active-market",
                null,
                null,
                List.of(
                        new PriceChangeDto("up-token", null, null, null, null, "0.59", "0.60"),
                        new PriceChangeDto("down-token", null, null, null, null, "0.40", "0.41")
                ),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        method.invoke(runtime, message, "active-market", Map.of("up-token", "Up", "down-token", "Down"));

        assertEquals(new BigDecimal("0.59"), runtime.context().latestPriceState().byTokenId("up-token").orElseThrow().bid());
        assertEquals(new BigDecimal("0.60"), runtime.context().latestPriceState().byTokenId("up-token").orElseThrow().ask());
        assertEquals(new BigDecimal("0.40"), runtime.context().latestPriceState().byTokenId("down-token").orElseThrow().bid());
        assertEquals(new BigDecimal("0.41"), runtime.context().latestPriceState().byTokenId("down-token").orElseThrow().ask());
    }

    private GammaMarketDto marketEndingAt(Instant endDate) {
        return new GammaMarketDto(
                "expired-market",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown-5m-1",
                endDate,
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

    private BotRuntime runtimeWithEmptyMarketLookup() {
        BotConfigEntity config = BotConfigEntity.create(
                "test-bot",
                MarketFamily.BTC_5M,
                "cost-aware-momentum-paper",
                true
        );
        GammaClient gammaClient = mock(GammaClient.class);
        when(gammaClient.getMarketBySlug(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        when(gammaClient.searchBitcoinUpDownMarkets()).thenReturn(Flux.empty());
        return new BotRuntime(
                config,
                gammaClient,
                mock(ClobClient.class),
                mock(PolymarketWebSocketClient.class),
                mock(ObjectMapper.class),
                new MarketSelectionProperties("5m", 60L, 300L, 0L),
                mock(MarketPersistenceService.class),
                mock(MarketResolutionService.class)
        );
    }

    private GammaMarketDto activeMarket() {
        return new GammaMarketDto(
                "active-market",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown-5m-1",
                Instant.now().plusSeconds(120),
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
