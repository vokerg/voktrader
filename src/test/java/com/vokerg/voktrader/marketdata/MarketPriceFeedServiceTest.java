package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.polymarket.dto.PriceChangeDto;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketPriceFeedServiceTest {

    @Test
    void sharesOneSubscriptionForMultipleBotsOnSameMarket() {
        ClobClient clobClient = mock(ClobClient.class);
        PolymarketWebSocketClient webSocketClient = mock(PolymarketWebSocketClient.class);
        Disposable subscription = mock(Disposable.class);
        when(clobClient.getOrderBook(anyString())).thenReturn(Mono.empty());
        when(webSocketClient.subscribeToMarketData(anyList(), any())).thenReturn(subscription);
        MarketPriceFeedService service = new MarketPriceFeedService(
                clobClient,
                webSocketClient,
                new NoopMarketOrderBookMonitor(),
                mock(PriceSnapshotService.class),
                mock(TradingEventLogger.class)
        );
        GammaMarketDto market = market("market-1");
        MarketTokenMap tokenMap = new MarketTokenMap(
                List.of("up-token", "down-token"),
                Map.of("up-token", "Up", "down-token", "Down")
        );

        MarketPriceFeedHandle first = service.acquire(1L, market, tokenMap, (marketId, message) -> {});
        MarketPriceFeedHandle second = service.acquire(2L, market, tokenMap, (marketId, message) -> {});

        verify(webSocketClient).subscribeToMarketData(anyList(), any());
        first.close();
        second.close();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void priceChangeMessageUpdatesSharedLatestState() {
        ClobClient clobClient = mock(ClobClient.class);
        PolymarketWebSocketClient webSocketClient = mock(PolymarketWebSocketClient.class);
        when(clobClient.getOrderBook(anyString())).thenReturn(Mono.empty());
        when(webSocketClient.subscribeToMarketData(anyList(), any())).thenReturn(mock(Disposable.class));
        MarketPriceFeedService service = new MarketPriceFeedService(
                clobClient,
                webSocketClient,
                new NoopMarketOrderBookMonitor(),
                mock(PriceSnapshotService.class),
                mock(TradingEventLogger.class)
        );
        MarketTokenMap tokenMap = new MarketTokenMap(
                List.of("up-token", "down-token"),
                Map.of("up-token", "Up", "down-token", "Down")
        );
        MarketPriceFeedHandle handle = service.acquire(1L, market("market-1"), tokenMap, (marketId, message) -> {});
        ArgumentCaptor<Consumer<MarketWsMessageDto>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(webSocketClient).subscribeToMarketData(anyList(), captor.capture());
        MarketWsMessageDto message = new MarketWsMessageDto(
                "price_change",
                null,
                "market-1",
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

        captor.getValue().accept(message);

        assertEquals(new BigDecimal("0.59"), handle.latestPriceState().byTokenId("up-token").orElseThrow().bid());
        assertEquals(new BigDecimal("0.60"), handle.latestPriceState().byTokenId("up-token").orElseThrow().ask());
        assertEquals(new BigDecimal("0.40"), handle.latestPriceState().byTokenId("down-token").orElseThrow().bid());
        assertEquals(new BigDecimal("0.41"), handle.latestPriceState().byTokenId("down-token").orElseThrow().ask());
    }

    @Test
    void snapshotsAreSavedOncePerFeedWithoutBotScope() {
        ClobClient clobClient = mock(ClobClient.class);
        PolymarketWebSocketClient webSocketClient = mock(PolymarketWebSocketClient.class);
        PriceSnapshotService priceSnapshotService = mock(PriceSnapshotService.class);
        when(clobClient.getOrderBook(anyString())).thenReturn(Mono.empty());
        when(webSocketClient.subscribeToMarketData(anyList(), any())).thenReturn(mock(Disposable.class));
        MarketPriceFeedService service = new MarketPriceFeedService(
                clobClient,
                webSocketClient,
                new NoopMarketOrderBookMonitor(),
                priceSnapshotService,
                mock(TradingEventLogger.class)
        );
        MarketTokenMap tokenMap = new MarketTokenMap(
                List.of("up-token", "down-token"),
                Map.of("up-token", "Up", "down-token", "Down")
        );
        MarketPriceFeedHandle first = service.acquire(1L, market("market-1"), tokenMap, (marketId, message) -> {});
        MarketPriceFeedHandle second = service.acquire(2L, market("market-1"), tokenMap, (marketId, message) -> {});
        first.latestPriceState().update("up-token", "Up", new BigDecimal("0.49"), new BigDecimal("0.50"));
        second.latestPriceState().update("down-token", "Down", new BigDecimal("0.50"), new BigDecimal("0.51"));

        service.snapshotFeeds();

        verify(priceSnapshotService).saveSnapshot(
                isNull(),
                org.mockito.ArgumentMatchers.eq("market-1"),
                any(),
                any(),
                any(),
                any()
        );
    }

    private GammaMarketDto market(String id) {
        return new GammaMarketDto(
                id,
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
