package com.vokerg.voktrader.bot;

import com.vokerg.voktrader.config.MarketSelectionProperties;
import com.vokerg.voktrader.market.MarketPersistenceService;
import com.vokerg.voktrader.marketdata.MarketPriceFeedService;
import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.resolution.MarketResolutionService;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

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
                null,
                null,
                true
        );
        GammaClient gammaClient = mock(GammaClient.class);
        MarketPersistenceService marketPersistenceService = mock(MarketPersistenceService.class);
        MarketSelectionProperties marketSelectionProperties = new MarketSelectionProperties("5m", 60L, 300L, 0L);
        BotRuntime runtime = new BotRuntime(
                config,
                gammaClient,
                mock(MarketPriceFeedService.class),
                mock(ObjectMapper.class),
                marketSelectionProperties,
                marketPersistenceService,
                mock(MarketResolutionService.class),
                mock(TradingEventLogger.class)
        );
        GammaMarketDto expiredMarket = marketEndingAt(Instant.now().minusSeconds(1));
        when(gammaClient.getMarketBySlug(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        when(gammaClient.searchBitcoinUpDownMarkets()).thenReturn(Flux.empty());

        runtime.context().trackedMarketState().startTracking(expiredMarket);

        runtime.ensureMarketIsTracked();

        verify(marketPersistenceService).markStopped("expired-market");
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
                null,
                null,
                true
        );
        GammaClient gammaClient = mock(GammaClient.class);
        when(gammaClient.getMarketBySlug(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        when(gammaClient.searchBitcoinUpDownMarkets()).thenReturn(Flux.empty());
        return new BotRuntime(
                config,
                gammaClient,
                mock(MarketPriceFeedService.class),
                mock(ObjectMapper.class),
                new MarketSelectionProperties("5m", 60L, 300L, 0L),
                mock(MarketPersistenceService.class),
                mock(MarketResolutionService.class),
                mock(TradingEventLogger.class)
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
