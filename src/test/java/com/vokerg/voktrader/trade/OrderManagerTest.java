package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderManagerTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository tradeFillRepository = mock(TradeFillRepository.class);
    private final TradeEventRepository tradeEventRepository = mock(TradeEventRepository.class);
    private final PythonExecutorClient pythonExecutorClient = mock(PythonExecutorClient.class);
    private final OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
    private final ExecutorProperties executorProperties = new ExecutorProperties();
    private final List<TradeEventEntity> savedEvents = new ArrayList<>();
    private final OrderCancellationEventEmitter cancellationEventEmitter = new OrderCancellationEventEmitter(
            tradeEventRepository,
            new ObjectMapper()
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderManager orderManager = new OrderManager(
            tradeRepository,
            tradeOrderRepository,
            tradeFillRepository,
            pythonExecutorClient,
            executorProperties,
            reconciliationService,
            cancellationEventEmitter,
            objectMapper
    );

    @BeforeEach
    void setUp() {
        savedEvents.clear();
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
            TradeEntity trade = invocation.getArgument(0);
            if (trade.getId() == null) {
                ReflectionTestUtils.setField(trade, "id", 1L);
            }
            return trade;
        });
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> {
            TradeOrderEntity order = invocation.getArgument(0);
            if (order.getId() == null) {
                ReflectionTestUtils.setField(order, "id", 10L);
            }
            return order;
        });
        when(tradeFillRepository.findByOrderId(any())).thenReturn(List.of());
        when(tradeFillRepository.save(any(TradeFillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
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

        OrderLifecycleResult result = orderManager.submitOrder(intent(TradeSide.BUY), ExecutionMode.LIVE);

        assertThat(result.success()).isTrue();
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.SUBMITTED);
        verify(reconciliationService).reconcileOrder(any(TradeOrderEntity.class), eq(OrderReconciliationSource.POST_SUBMIT));
    }

    @Test
    void rejectedOrderBecomesRejectedAndTradeFailed() {
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(ExecutorOrderResponse.rejected("exchange rejected"));

        OrderLifecycleResult result = orderManager.submitOrder(intent(TradeSide.BUY), ExecutionMode.LIVE);

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

        orderManager.submitOrder(intent(TradeSide.BUY), ExecutionMode.LIVE);

        ArgumentCaptor<TradeOrderEntity> orderCaptor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        org.mockito.Mockito.verify(reconciliationService).applyImmediateFill(any(TradeEntity.class), orderCaptor.capture(), any(ExecutorOrderResponse.class));
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(orderCaptor.getValue().getRemoteOrderId()).isEqualTo("remote-1");
        ArgumentCaptor<TradeFillEntity> fillCaptor = ArgumentCaptor.forClass(TradeFillEntity.class);
        verify(tradeFillRepository).save(fillCaptor.capture());
        TradeFillEntity fill = fillCaptor.getValue();
        assertThat(fill.getTradeId()).isEqualTo(1L);
        assertThat(fill.getOrderId()).isEqualTo(10L);
        assertThat(fill.getExchangeOrderId()).isEqualTo("remote-1");
        assertThat(fill.getSide()).isEqualTo(TradeSide.BUY);
        assertThat(fill.getPrice()).isEqualByComparingTo("0.50");
        assertThat(fill.getShares()).isEqualByComparingTo("2");
        assertThat(fill.getAmountUsd()).isEqualByComparingTo("1.00");
        assertThat(fill.getFeeUsd()).isEqualByComparingTo("0.01");
        assertThat(fill.getRawFill()).isEqualTo("{}");
        verify(reconciliationService).reconcileOrder(any(TradeOrderEntity.class), eq(OrderReconciliationSource.POST_FILL_AUDIT));
    }

    @Test
    void cancelOrderMarksCancelRequestedAndLeavesFinalStateForReconciliation() {
        TradeOrderEntity order = TradeOrderEntity.fromIntent(1L, intent(TradeSide.BUY), ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1");
        ReflectionTestUtils.setField(order, "id", 6358L);
        order.markSubmitted("remote-1", "{}");
        TradeEntity trade = TradeEntity.fromIntent(intent(TradeSide.BUY), ExecutionMode.LIVE);
        when(tradeOrderRepository.findByLocalOrderId("local-1")).thenReturn(Optional.of(order));
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(pythonExecutorClient.cancelOrder("remote-1")).thenReturn(new ExecutorCancelOrderResponse(true, "remote-1", "CANCELLED", "{}", null));
        when(reconciliationService.reconcileOrder(eq(order), eq(OrderReconciliationSource.POST_CANCEL))).thenAnswer(invocation -> {
            order.markCancelled("manual/explicit cancel requested", "{}");
            return OrderLifecycleResult.of(trade, order, true, "CANCELLED");
        });

        OrderLifecycleResult result = orderManager.cancelOrder("local-1");

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        verify(reconciliationService).reconcileOrder(order, OrderReconciliationSource.POST_CANCEL);
        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .containsExactly(OrderCancellationEventEmitter.CANCEL_REQUESTED_EVENT);
    }

    @Test
    void cancelOrderDoesNotOverwriteRawResponseWithStringNull() {
        TradeOrderEntity order = TradeOrderEntity.fromIntent(1L, intent(TradeSide.BUY), ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1");
        order.markSubmitted("remote-1", "{\"submitted\":true}");
        TradeEntity trade = TradeEntity.fromIntent(intent(TradeSide.BUY), ExecutionMode.LIVE);
        when(tradeOrderRepository.findByLocalOrderId("local-1")).thenReturn(Optional.of(order));
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(pythonExecutorClient.cancelOrder("remote-1")).thenReturn(new ExecutorCancelOrderResponse(true, "remote-1", "CANCELLED", "null", null));
        when(reconciliationService.reconcileOrder(eq(order), eq(OrderReconciliationSource.POST_CANCEL)))
                .thenReturn(OrderLifecycleResult.of(trade, order, true, "CANCELLED"));

        orderManager.cancelOrder("local-1");

        assertThat(order.getRawResponse()).isEqualTo("{\"submitted\":true}");
        verify(reconciliationService).reconcileOrder(order, OrderReconciliationSource.POST_CANCEL);
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
