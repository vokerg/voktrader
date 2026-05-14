package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderManagerTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeEventRepository tradeEventRepository = mock(TradeEventRepository.class);
    private final PythonExecutorClient pythonExecutorClient = mock(PythonExecutorClient.class);
    private final OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
    private final ExecutorProperties executorProperties = new ExecutorProperties();
    private final List<TradeEventEntity> savedEvents = new ArrayList<>();
    private final OrderCancellationEventEmitter cancellationEventEmitter = new OrderCancellationEventEmitter(
            tradeEventRepository,
            new ObjectMapper()
    );
    private final OrderManager orderManager = new OrderManager(
            tradeRepository,
            tradeOrderRepository,
            pythonExecutorClient,
            executorProperties,
            reconciliationService,
            cancellationEventEmitter
    );

    @BeforeEach
    void setUp() {
        savedEvents.clear();
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeEventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> {
            TradeEventEntity event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });
        when(tradeEventRepository.existsByTradeOrderIdAndEventType(any(), any())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            String eventType = invocation.getArgument(1);
            return savedEvents.stream().anyMatch(event -> orderId.equals(event.getTradeOrderId())
                    && eventType.equals(event.getEventType()));
        });
    }

    @Test
    void acceptedUnfilledOrderBecomesSubmittedAndEntryPending() {
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(new ExecutorOrderResponse(
                true,
                false,
                "SUBMITTED",
                "remote-1",
                new BigDecimal("0.51"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "submitted",
                "{}",
                Instant.parse("2026-05-09T12:00:00Z")
        ));

        OrderLifecycleResult result = orderManager.submitOrder(intent(TradeSide.BUY), ExecutionMode.LIVE_TINY);

        assertThat(result.success()).isTrue();
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.SUBMITTED);
    }

    @Test
    void rejectedOrderBecomesRejectedAndTradeFailed() {
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(ExecutorOrderResponse.rejected("exchange rejected"));

        OrderLifecycleResult result = orderManager.submitOrder(intent(TradeSide.BUY), ExecutionMode.LIVE_TINY);

        assertThat(result.success()).isFalse();
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.FAILED);
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.REJECTED);
    }

    @Test
    void filledOrderDelegatesImmediateFillApplication() {
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "remote-1",
                new BigDecimal("0.50"),
                new BigDecimal("2"),
                new BigDecimal("1.00"),
                new BigDecimal("0.01"),
                "matched",
                "{}",
                Instant.parse("2026-05-09T12:00:00Z")
        ));

        orderManager.submitOrder(intent(TradeSide.BUY), ExecutionMode.LIVE_TINY);

        ArgumentCaptor<TradeOrderEntity> orderCaptor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        org.mockito.Mockito.verify(reconciliationService).applyImmediateFill(any(TradeEntity.class), orderCaptor.capture(), any(ExecutorOrderResponse.class));
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(orderCaptor.getValue().getRemoteOrderId()).isEqualTo("remote-1");
    }

    @Test
    void cancelOrderMarksCancelRequestedAndLeavesFinalStateForReconciliation() {
        TradeOrderEntity order = TradeOrderEntity.fromIntent(1L, intent(TradeSide.BUY), ExecutionMode.LIVE_TINY, TradeVenue.POLYMARKET, "local-1");
        ReflectionTestUtils.setField(order, "id", 6358L);
        order.markSubmitted("remote-1", "{}");
        TradeEntity trade = TradeEntity.fromIntent(intent(TradeSide.BUY), ExecutionMode.LIVE_TINY);
        when(tradeOrderRepository.findByLocalOrderId("local-1")).thenReturn(Optional.of(order));
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(pythonExecutorClient.cancelOrder("remote-1")).thenReturn(new ExecutorCancelOrderResponse(true, "remote-1", "CANCELLED", "{}", null));

        OrderLifecycleResult result = orderManager.cancelOrder("local-1");

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .containsExactly(
                        OrderCancellationEventEmitter.CANCEL_REQUESTED_EVENT,
                        OrderCancellationEventEmitter.CANCELLED_EVENT
                );
        assertThat(savedEvents.getLast().getPayloadJson())
                .contains("\"previousStatus\":\"CANCEL_REQUESTED\"")
                .contains("\"resolvedStatus\":\"CANCELLED\"")
                .contains("\"cancelReason\":\"manual/explicit cancel requested\"");
    }

    private TradeIntent intent(TradeSide side) {
        return side == TradeSide.BUY
                ? TradeIntent.buy(null, market(), price(), new BigDecimal("1.00"), TradeOrderType.GTC, true, new BigDecimal("0.50"), "strategy", "rule", "entry")
                : TradeIntent.sell(null, market(), price(), new BigDecimal("2"), TradeOrderType.GTC, true, new BigDecimal("0.50"), "strategy", "rule", "exit");
    }

    private GammaMarketDto market() {
        return new GammaMarketDto("market-id", "Question", "condition-id", "slug", Instant.parse("2026-05-09T12:05:00Z"), true, false, true, false, null, null, null, null);
    }

    private OutcomePrice price() {
        return new OutcomePrice("token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"), new BigDecimal("0.02"), Instant.parse("2026-05-09T12:00:00Z"));
    }
}
