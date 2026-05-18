package com.vokerg.voktrader.market;

import com.vokerg.voktrader.outbox.MarketResolvedOutboxPayload;
import com.vokerg.voktrader.outbox.OutboxEventService;
import com.vokerg.voktrader.outbox.OutboxEventType;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketResolutionServiceTest {
    private final MarketRepository marketRepository = mock(MarketRepository.class);
    private final OutboxEventService outboxEventService = mock(OutboxEventService.class);
    private final TradingEventLogger eventLogger = mock(TradingEventLogger.class);
    private final MarketResolutionService service = new MarketResolutionService(
            marketRepository,
            outboxEventService,
            eventLogger
    );

    @Test
    void resolvingMarketPersistsResolvedStateAndWritesOutboxEvent() {
        MarketEntity market = new MarketEntity();
        market.setPolymarketMarketId("market-1");
        when(marketRepository.findByPolymarketMarketId("market-1")).thenReturn(Optional.of(market));

        service.resolveMarket("market-1", "asset-yes", "Yes", "websocket");

        assertThat(market.isResolved()).isTrue();
        assertThat(market.isClosed()).isTrue();
        assertThat(market.isActive()).isFalse();
        assertThat(market.isAcceptingOrders()).isFalse();
        assertThat(market.getTrackingStatus()).isEqualTo(MarketTrackingStatus.STOPPED);
        assertThat(market.getResolutionStatus()).isEqualTo(MarketResolutionStatus.RESOLVED);
        assertThat(market.getWinningOutcome()).isEqualTo("Yes");
        assertThat(market.getWinningAssetId()).isEqualTo("asset-yes");
        assertThat(market.getResolutionSource()).isEqualTo("websocket");
        assertThat(market.getResolvedAt()).isNotNull();
        verify(marketRepository).save(market);

        ArgumentCaptor<MarketResolvedOutboxPayload> payload =
                ArgumentCaptor.forClass(MarketResolvedOutboxPayload.class);
        verify(outboxEventService).enqueueOnce(
                eq(OutboxEventType.MARKET_RESOLVED),
                eq("MARKET_RESOLVED:market-1"),
                eq("MARKET"),
                eq("market-1"),
                payload.capture()
        );
        assertThat(payload.getValue().marketId()).isEqualTo("market-1");
        assertThat(payload.getValue().winningOutcome()).isEqualTo("Yes");
        assertThat(payload.getValue().source()).isEqualTo("websocket");
    }

    @Test
    void duplicateResolutionIsIdempotentAndOnlyRequestsSameOutboxKey() {
        MarketEntity market = new MarketEntity();
        market.setPolymarketMarketId("market-1");
        market.setResolved(true);
        market.setResolutionStatus(MarketResolutionStatus.RESOLVED);
        market.setWinningOutcome("Yes");
        market.setWinningAssetId("asset-yes");
        market.setResolutionSource("websocket");
        when(marketRepository.findByPolymarketMarketId("market-1")).thenReturn(Optional.of(market));

        service.resolveMarket("market-1", "asset-no", "No", "gamma_poll");

        assertThat(market.getWinningOutcome()).isEqualTo("Yes");
        verify(marketRepository, org.mockito.Mockito.never()).save(any(MarketEntity.class));
        verify(outboxEventService).enqueueOnce(
                eq(OutboxEventType.MARKET_RESOLVED),
                eq("MARKET_RESOLVED:market-1"),
                eq("MARKET"),
                eq("market-1"),
                any(MarketResolvedOutboxPayload.class)
        );
    }

    @Test
    void ignoresIncompleteResolution() {
        service.resolveMarket("market-1", "asset-yes", "", "websocket");

        verifyNoInteractions(marketRepository, outboxEventService, eventLogger);
    }
}
