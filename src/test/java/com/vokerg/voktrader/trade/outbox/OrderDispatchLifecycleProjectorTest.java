package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.OrderReconciliationService;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderDispatchLifecycleProjectorTest {
    @Test
    void deterministicExitRejectionRestoresOpenPosition() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        TradeFillRepository fillRepository = mock(TradeFillRepository.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        OrderDispatchLifecycleProjector projector = new OrderDispatchLifecycleProjector(
                tradeRepository, orderRepository, fillRepository, reconciliationService
        );

        TradeIntent entry = buyIntent();
        TradeEntity trade = TradeEntity.fromIntent(entry, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 11L);
        trade.markOpen(
                new BigDecimal("0.50"), new BigDecimal("2"), new BigDecimal("1.00"),
                BigDecimal.ZERO, Instant.parse("2026-08-06T17:00:00Z")
        );
        TradeIntent exit = sellIntent();
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                11L, exit, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "vok-exit"
        );
        ReflectionTestUtils.setField(order, "id", 22L);
        trade.markExitPending();

        when(orderRepository.findByClientOrderId("vok-exit")).thenReturn(Optional.of(order));
        when(tradeRepository.findById(11L)).thenReturn(Optional.of(trade));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        projector.recordResponse(
                claim("vok-exit"),
                ExecutorOrderResponse.rejected("exchange rejected exit")
        );

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.REJECTED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        verify(orderRepository).save(order);
        verify(tradeRepository).save(trade);
    }

    private OrderDispatchClaim claim(String clientOrderId) {
        Instant now = Instant.parse("2026-08-06T17:30:00Z");
        return new OrderDispatchClaim(1L, clientOrderId, "worker", ExecutionMode.LIVE, "{}", now, now);
    }

    private TradeIntent buyIntent() {
        return TradeIntent.buy(
                null, market(), price(), new BigDecimal("1.00"), new BigDecimal("2"),
                TradeOrderType.FOK, false, new BigDecimal("0.50"),
                "strategy", "entry", "open"
        );
    }

    private TradeIntent sellIntent() {
        return TradeIntent.sell(
                null, market(), price(), new BigDecimal("2"),
                TradeOrderType.FOK, false, new BigDecimal("0.49"),
                "strategy", "exit", "close"
        );
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id", "Question", "condition-id", "slug",
                Instant.parse("2026-08-06T18:00:00Z"),
                true, false, true, false, null, null, null, null
        );
    }

    private OutcomePrice price() {
        return new OutcomePrice(
                "token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.50"),
                new BigDecimal("0.01"), Instant.parse("2026-08-06T17:20:00Z")
        );
    }
}
