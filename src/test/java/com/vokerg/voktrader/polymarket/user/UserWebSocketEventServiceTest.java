package com.vokerg.voktrader.polymarket.user;

import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserWebSocketEventServiceTest {
    private UserWebSocketEventRepository eventRepository;
    private TradeOrderRepository tradeOrderRepository;
    private UserWebSocketEventService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(UserWebSocketEventRepository.class);
        tradeOrderRepository = mock(TradeOrderRepository.class);
        service = new UserWebSocketEventService(eventRepository, tradeOrderRepository);
    }

    @Test
    void placementPersistsAndProjectsExactRemoteOrderWithinProcessingCycle() {
        TradeOrderEntity order = mock(TradeOrderEntity.class);
        when(order.getId()).thenReturn(42L);
        when(order.getStatus()).thenReturn(TradeOrderStatus.SUBMITTED);
        when(tradeOrderRepository.findByRemoteOrderId("remote-1")).thenReturn(Optional.of(order));

        UserWebSocketMessage message = orderMessage("remote-1", "PLACEMENT", "0", "1770000000000");
        UserWebSocketEventService.ProcessingResult result = service.process(
                message,
                "{\"event_type\":\"order\",\"id\":\"remote-1\",\"type\":\"PLACEMENT\"}",
                3L,
                Instant.parse("2026-09-19T15:00:00Z")
        );

        assertThat(result.persisted()).isTrue();
        assertThat(result.projectedOrderCount()).isEqualTo(1);
        verify(order).recordUserWebSocketEvent(
                "order",
                "PLACEMENT",
                null,
                Instant.ofEpochMilli(1770000000000L),
                3L
        );
        verify(order).markResting(any(String.class));
        verify(tradeOrderRepository).save(order);

        ArgumentCaptor<UserWebSocketEventEntity> eventCaptor =
                ArgumentCaptor.forClass(UserWebSocketEventEntity.class);
        verify(eventRepository, org.mockito.Mockito.atLeastOnce()).save(eventCaptor.capture());
        UserWebSocketEventEntity stored = eventCaptor.getAllValues().getLast();
        assertThat(stored.isProcessed()).isTrue();
        assertThat(stored.getTradeOrderId()).isEqualTo(42L);
        assertThat(stored.getRemoteOrderId()).isEqualTo("remote-1");
    }

    @Test
    void duplicateRemoteLifecycleEventIsIgnored() {
        when(eventRepository.existsByDedupeKey(any(String.class))).thenReturn(true);

        UserWebSocketEventService.ProcessingResult result = service.process(
                orderMessage("remote-1", "UPDATE", "2", "1770000000000"),
                "{}",
                1L,
                Instant.parse("2026-09-19T15:00:00Z")
        );

        assertThat(result.duplicate()).isTrue();
        verify(eventRepository, never()).save(any());
        verify(tradeOrderRepository, never()).findByRemoteOrderId(any(String.class));
    }

    @Test
    void matchedTradeIsPrimaryLifecycleEvidenceButDoesNotSettleInventory() {
        TradeOrderEntity order = mock(TradeOrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(tradeOrderRepository.findByRemoteOrderId("maker-order")).thenReturn(Optional.of(order));

        UserWebSocketMessage message = new UserWebSocketMessage(
                "trade",
                "trade-1",
                "market-1",
                "token-1",
                "BUY",
                "0.57",
                "10",
                "MATCHED",
                "TRADE",
                null,
                null,
                null,
                List.of(new UserWebSocketMessage.MakerOrder("maker-order", "10", "0.57")),
                "1770000000000",
                null
        );

        UserWebSocketEventService.ProcessingResult result = service.process(
                message,
                "{\"event_type\":\"trade\",\"id\":\"trade-1\",\"status\":\"MATCHED\"}",
                4L,
                Instant.parse("2026-09-19T15:00:00Z")
        );

        assertThat(result.projectedOrderCount()).isEqualTo(1);
        verify(order).recordUserWebSocketEvent(
                "trade",
                "MATCHED",
                "trade-1",
                Instant.ofEpochMilli(1770000000000L),
                4L
        );
        verify(order, never()).markFilled(any(), any(), any(), any());
        verify(order, never()).applyFillState(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    private UserWebSocketMessage orderMessage(
            String orderId,
            String type,
            String sizeMatched,
            String timestamp
    ) {
        return new UserWebSocketMessage(
                "order",
                orderId,
                "market-1",
                "token-1",
                "BUY",
                "0.57",
                null,
                null,
                type,
                "10",
                sizeMatched,
                null,
                List.of(),
                timestamp,
                null
        );
    }
}
