package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.client.MarketWebSocketObserver;
import com.vokerg.voktrader.polymarket.client.PolymarketWebSocketClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;
import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketPriceFeedSupervisionTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void forcedDisconnectHidesStateUntilCompleteRestReseed() {
        ClobClient clobClient = mock(ClobClient.class);
        PolymarketWebSocketClient webSocketClient = mock(PolymarketWebSocketClient.class);
        when(clobClient.getOrderBook(anyString())).thenAnswer(invocation -> Mono.just(book(invocation.getArgument(0))));
        when(webSocketClient.subscribeToMarketData(anyList(), any())).thenReturn(mock(Disposable.class));

        MarketPriceFeedService service = new MarketPriceFeedService(
                clobClient,
                webSocketClient,
                new NoopMarketOrderBookMonitor(),
                mock(PriceSnapshotService.class),
                mock(MarketDepthSnapshotService.class),
                mock(TradingEventLogger.class)
        );
        MarketPriceFeedHandle handle = service.acquire(
                1L,
                market(),
                new MarketTokenMap(
                        List.of("up-token", "down-token"),
                        Map.of("up-token", "Up", "down-token", "Down")
                ),
                (marketId, message) -> { }
        );

        ArgumentCaptor<Consumer<MarketWsMessageDto>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(webSocketClient).subscribeToMarketData(anyList(), captor.capture());
        MarketWebSocketObserver observer = (MarketWebSocketObserver) captor.getValue();
        observer.onConnected();

        assertFalse(handle.latestPriceState().allByTokenId().isEmpty());
        observer.onDisconnected(new IllegalStateException("forced disconnect"));
        assertTrue(handle.latestPriceState().allByTokenId().isEmpty());

        observer.onConnected();
        assertTrue(service.supervisionSnapshot("market-1").orElseThrow().strategyPaused());
        service.superviseFeeds();

        MarketStreamSupervisor.Snapshot snapshot = service.supervisionSnapshot("market-1").orElseThrow();
        assertEquals(2L, snapshot.generation());
        assertEquals(1L, snapshot.reconnectCount());
        assertEquals(1L, snapshot.reseedCount());
        assertFalse(snapshot.strategyPaused());
        assertEquals(2, handle.latestPriceState().allByTokenId().size());
    }

    private OrderBookDto book(String tokenId) {
        boolean up = "up-token".equals(tokenId);
        return new OrderBookDto(
                "market-1",
                tokenId,
                "1753444800000",
                "hash-" + tokenId,
                List.of(new PriceLevelDto(up ? "0.49" : "0.39", "2")),
                List.of(new PriceLevelDto(up ? "0.51" : "0.41", "3")),
                "1",
                "0.01",
                false,
                null
        );
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-1",
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
