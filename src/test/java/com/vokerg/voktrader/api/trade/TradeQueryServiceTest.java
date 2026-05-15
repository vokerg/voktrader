package com.vokerg.voktrader.api.trade;

import com.vokerg.voktrader.api.trade.dto.TradeDetailResponse;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeQueryServiceTest {
    @Test
    void detailIncludesOrdersAndEventsForTrade() {
        TradeEntity trade = trade(5298L);
        TradeOrderEntity order = order(5298L);
        TradeEventEntity event = TradeEventEntity.of(5298L, 6340L, null, "ORDER_FILLED", "filled", "{\"ok\":true}");
        ReflectionTestUtils.setField(event, "id", 5L);

        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeFillRepository fillRepository = mock(TradeFillRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        TradeEventRepository eventRepository = mock(TradeEventRepository.class);
        when(tradeRepository.findById(5298L)).thenReturn(Optional.of(trade));
        when(fillRepository.findByTradeId(5298L)).thenReturn(List.of());
        when(orderRepository.findByTradeId(5298L)).thenReturn(List.of(order));
        when(eventRepository.findByTradeIdOrderByCreatedAtAsc(5298L)).thenReturn(List.of(event));

        TradeQueryService service = new TradeQueryService(
                tradeRepository,
                fillRepository,
                orderRepository,
                eventRepository
        );

        TradeDetailResponse response = service.get(5298L);

        assertThat(response.orders()).singleElement()
                .satisfies(returnedOrder -> {
                    assertThat(returnedOrder.id()).isEqualTo(6340L);
                    assertThat(returnedOrder.tradeId()).isEqualTo(5298L);
                });
        assertThat(response.events()).singleElement()
                .satisfies(returnedEvent -> {
                    assertThat(returnedEvent.id()).isEqualTo(5L);
                    assertThat(returnedEvent.tradeId()).isEqualTo(5298L);
                    assertThat(returnedEvent.eventType()).isEqualTo("ORDER_FILLED");
                });
    }

    private TradeEntity trade(Long id) {
        TradeIntent intent = intent();
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", id);
        return trade;
    }

    private TradeOrderEntity order(Long tradeId) {
        TradeOrderEntity order = TradeOrderEntity.fromIntent(tradeId, intent(), ExecutionMode.LIVE, null, "entry-local");
        ReflectionTestUtils.setField(order, "id", 6340L);
        return order;
    }

    private TradeIntent intent() {
        Instant now = Instant.parse("2026-05-13T18:26:42Z");
        return new TradeIntent(
                67L,
                "MK_GTD_EDGE_A",
                "mk-gtd-edge-a-entry",
                "2243638",
                "btc-updown-5m-1778696700",
                "Bitcoin Up or Down",
                null,
                "token-up",
                "Up",
                TradeSide.BUY,
                new BigDecimal("2.50"),
                new BigDecimal("5.00"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.50"),
                new BigDecimal("0.50"),
                new BigDecimal("0.51"),
                new BigDecimal("0.01"),
                new BigDecimal("0.505"),
                now,
                1L,
                now,
                now.plusSeconds(197),
                197L,
                "reason"
        );
    }
}
