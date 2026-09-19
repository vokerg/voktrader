package com.vokerg.voktrader.polymarket.user;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserWebSocketSafetyServiceTest {
    @Test
    void activeLiveOrderRequiresHealthyPrivateStream() {
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        UserWebSocketEventRepository eventRepository = mock(UserWebSocketEventRepository.class);
        when(orderRepository.findByModeAndVenueAndStatusInOrderByUpdatedAtAsc(
                org.mockito.ArgumentMatchers.eq(ExecutionMode.LIVE),
                org.mockito.ArgumentMatchers.eq(TradeVenue.POLYMARKET),
                anyList()
        )).thenReturn(List.of(mock(TradeOrderEntity.class)));

        UserWebSocketSafetyService service = new UserWebSocketSafetyService(orderRepository, eventRepository);

        assertThat(service.exposureSnapshot().activeLiveOrders()).isTrue();
        assertThat(service.requiresHealthyStream()).isTrue();
    }

    @Test
    void unresolvedMatchedTradeRequiresHealthyPrivateStreamWithoutOpenOrder() {
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        UserWebSocketEventRepository eventRepository = mock(UserWebSocketEventRepository.class);
        when(orderRepository.findByModeAndVenueAndStatusInOrderByUpdatedAtAsc(
                org.mockito.ArgumentMatchers.eq(ExecutionMode.LIVE),
                org.mockito.ArgumentMatchers.eq(TradeVenue.POLYMARKET),
                anyList()
        )).thenReturn(List.of());
        when(eventRepository.existsUnresolvedProvisionalTradeEvent()).thenReturn(true);

        UserWebSocketSafetyService service = new UserWebSocketSafetyService(orderRepository, eventRepository);

        assertThat(service.exposureSnapshot().unresolvedProvisionalTradeEvents()).isTrue();
        assertThat(service.requiresHealthyStream()).isTrue();
    }
}
