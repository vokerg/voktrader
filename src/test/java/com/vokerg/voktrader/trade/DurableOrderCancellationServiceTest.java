package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.outbox.OrderDispatchStateService;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableOrderCancellationServiceTest {
    @Test
    void cancelRequestCommitsBeforeRemoteCallAndOnlyReconciliationMakesCancellationTerminal() {
        Fixture fixture = fixture();
        ExecutorCancelOrderResponse response = new ExecutorCancelOrderResponse(
                true, "remote-1", "CANCELLED", "{\"status\":\"CANCELLED\"}", null
        );
        when(fixture.executorClient.cancelOrder("remote-1")).thenReturn(response);
        when(fixture.reconciliationService.reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        )).thenAnswer(invocation -> {
            TradeOrderStatus previous = fixture.order.getStatus();
            fixture.order.markCancelled("operator requested", "{\"status\":\"CANCELLED\"}");
            return OrderReconciliationResult.from(
                    fixture.order,
                    previous,
                    true,
                    true,
                    "CANCELLED",
                    0,
                    0,
                    List.of()
            );
        });

        OrderLifecycleResult result = fixture.service.cancel("local-1", "operator requested");

        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(fixture.order.getCancelReason()).isEqualTo("operator requested");
        InOrder ordering = inOrder(
                fixture.orderRepository,
                fixture.eventEmitter,
                fixture.executorClient,
                fixture.reconciliationService
        );
        ordering.verify(fixture.orderRepository).save(fixture.order);
        ordering.verify(fixture.eventEmitter).emitCancelRequested(
                fixture.trade,
                fixture.order,
                "operator requested",
                null
        );
        ordering.verify(fixture.executorClient).cancelOrder("remote-1");
        ordering.verify(fixture.reconciliationService).reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        );
    }

    @Test
    void cancelBeforeRemoteSubmissionCancelsOutboxAndNeverCallsExecutor() {
        Fixture fixture = fixture(Duration.ofSeconds(12), false);
        when(fixture.dispatchStateService.cancelBeforeSubmission(
                eq("local-1"), eq("operator requested"), any(Instant.class)
        )).thenReturn(true);

        OrderLifecycleResult result = fixture.service.cancel("local-1", "operator requested");

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(fixture.order.getCancelReason()).isEqualTo("operator requested");
        verify(fixture.executorClient, never()).cancelOrder(any());
        verify(fixture.reconciliationService).reconcileTradeAfterOrderState(fixture.trade, fixture.order);
        verify(fixture.eventEmitter).emitCancelRequested(
                fixture.trade, fixture.order, "operator requested", null
        );
        verify(fixture.eventEmitter).emitCancelled(
                eq(fixture.trade),
                eq(fixture.order),
                eq(TradeOrderStatus.CANCEL_REQUESTED),
                eq(TradeOrderStatus.CANCELLED),
                eq("operator requested"),
                eq(null)
        );
    }

    @Test
    void persistedCancelRequestedIsDispatchedAfterRestart() {
        Fixture fixture = fixture();
        fixture.order.markCancelRequested("restart recovery");
        when(fixture.orderRepository.findRecoverableCancellations(
                any(), eq(OrderCancellationEventEmitter.CANCEL_REQUESTED_EVENT)
        )).thenReturn(List.of(fixture.order));
        when(fixture.executorClient.cancelOrder("remote-1")).thenReturn(new ExecutorCancelOrderResponse(
                true, "remote-1", "CANCELLED", "{\"status\":\"CANCELLED\"}", null
        ));
        when(fixture.reconciliationService.reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        )).thenAnswer(invocation -> {
            TradeOrderStatus previous = fixture.order.getStatus();
            fixture.order.markCancelled("restart recovery", "{\"status\":\"CANCELLED\"}");
            return OrderReconciliationResult.from(
                    fixture.order,
                    previous,
                    true,
                    true,
                    "CANCELLED",
                    0,
                    0,
                    List.of()
            );
        });

        int resumed = fixture.service.resumePendingCancellations();

        assertThat(resumed).isEqualTo(1);
        assertThat(fixture.order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        verify(fixture.executorClient).cancelOrder("remote-1");
    }

    @Test
    void eventBackedCancelRecoverySurvivesStaleLifecycleWriteWithoutBlindRetry() {
        Fixture fixture = fixture();
        fixture.order.markResting("{\"status\":\"OPEN\"}");
        when(fixture.orderRepository.findRecoverableCancellations(
                any(), eq(OrderCancellationEventEmitter.CANCEL_REQUESTED_EVENT)
        )).thenReturn(List.of(fixture.order));
        when(fixture.reconciliationService.reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        )).thenAnswer(invocation -> {
            TradeOrderStatus previous = fixture.order.getStatus();
            fixture.order.markCancelled(fixture.order.getCancelReason(), "{\"status\":\"CANCELLED\"}");
            return OrderReconciliationResult.from(
                    fixture.order,
                    previous,
                    true,
                    true,
                    "CANCELLED",
                    0,
                    0,
                    List.of()
            );
        });

        int resumed = fixture.service.resumePendingCancellations();

        assertThat(resumed).isEqualTo(1);
        assertThat(fixture.order.getCancelReason()).contains("recovered durable cancellation request event");
        assertThat(fixture.order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        verify(fixture.executorClient, never()).cancelOrder(any());
        verify(fixture.reconciliationService).reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        );
    }

    @Test
    void staleSubmittingAfterRestartReconcilesBeforeAnyRetry() {
        Fixture fixture = fixture(Duration.ZERO);
        fixture.order.markCancelRequested("restart recovery");
        fixture.order.applyFillState(
                TradeOrderStatus.CANCEL_SUBMITTING,
                null,
                null,
                null,
                fixture.order.getRemainingShares(),
                null,
                false,
                null,
                null
        );
        when(fixture.orderRepository.findRecoverableCancellations(
                any(), eq(OrderCancellationEventEmitter.CANCEL_REQUESTED_EVENT)
        )).thenReturn(List.of(fixture.order));
        when(fixture.reconciliationService.reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        )).thenAnswer(invocation -> {
            TradeOrderStatus previous = fixture.order.getStatus();
            fixture.order.markCancelled("restart recovery", "{\"status\":\"CANCELLED\"}");
            return OrderReconciliationResult.from(
                    fixture.order,
                    previous,
                    true,
                    true,
                    "CANCELLED",
                    0,
                    0,
                    List.of()
            );
        });

        int resumed = fixture.service.resumePendingCancellations();

        assertThat(resumed).isEqualTo(1);
        assertThat(fixture.order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        verify(fixture.executorClient, never()).cancelOrder(any());
        verify(fixture.reconciliationService).reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        );
    }

    @Test
    void unsuccessfulCancelBecomesUnknownThenReconcilesBeforeRetry() {
        Fixture fixture = fixture();
        when(fixture.executorClient.cancelOrder("remote-1")).thenReturn(
                ExecutorCancelOrderResponse.failure("remote-1", "NETWORK_FAILURE", "connection reset")
        );
        when(fixture.reconciliationService.reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        )).thenAnswer(invocation -> {
            TradeOrderStatus previous = fixture.order.getStatus();
            fixture.order.markResting("{\"status\":\"OPEN\"}");
            return OrderReconciliationResult.from(
                    fixture.order,
                    previous,
                    true,
                    true,
                    "OPEN",
                    0,
                    0,
                    List.of()
            );
        });

        OrderLifecycleResult result = fixture.service.cancel("local-1", "operator requested");

        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCEL_REQUESTED);
        verify(fixture.executorClient).cancelOrder("remote-1");
        verify(fixture.reconciliationService).reconcileOrderDetailed(
                fixture.order,
                OrderReconciliationSource.POST_CANCEL
        );
    }

    private Fixture fixture() {
        return fixture(Duration.ofSeconds(12));
    }

    private Fixture fixture(Duration staleAfter) {
        return fixture(staleAfter, true);
    }

    private Fixture fixture(Duration staleAfter, boolean submitted) {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        PythonExecutorClient executorClient = mock(PythonExecutorClient.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        OrderCancellationEventEmitter eventEmitter = mock(OrderCancellationEventEmitter.class);
        OrderDispatchStateService dispatchStateService = mock(OrderDispatchStateService.class);
        TransactionOperations transactions = new ImmediateTransactions();

        TradeIntent intent = entryIntent();
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 11L);
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                11L, intent, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1"
        );
        ReflectionTestUtils.setField(order, "id", 22L);
        if (submitted) {
            order.markSubmitted("remote-1", "{}");
        }

        when(orderRepository.findByLocalOrderId("local-1")).thenReturn(Optional.of(order));
        when(orderRepository.findById(22L)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(order));
        when(tradeRepository.findById(11L)).thenReturn(Optional.of(trade));
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DurableOrderCancellationService service = new DurableOrderCancellationService(
                tradeRepository,
                orderRepository,
                executorClient,
                reconciliationService,
                eventEmitter,
                dispatchStateService,
                transactions,
                staleAfter
        );
        return new Fixture(
                service,
                tradeRepository,
                orderRepository,
                executorClient,
                reconciliationService,
                eventEmitter,
                dispatchStateService,
                trade,
                order
        );
    }

    private TradeIntent entryIntent() {
        GammaMarketDto market = new GammaMarketDto(
                "market-id", "Question", "condition-id", "slug",
                Instant.parse("2026-08-06T18:00:00Z"),
                true, false, true, false, null, null, null, null
        );
        OutcomePrice price = new OutcomePrice(
                "token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"),
                new BigDecimal("0.02"), Instant.parse("2026-08-06T17:20:00Z")
        );
        return TradeIntent.buy(
                null, market, price, new BigDecimal("1.00"),
                com.vokerg.voktrader.trade.model.TradeOrderType.FOK,
                false, new BigDecimal("0.51"), "strategy", "rule", "entry"
        );
    }

    private record Fixture(
            DurableOrderCancellationService service,
            TradeRepository tradeRepository,
            TradeOrderRepository orderRepository,
            PythonExecutorClient executorClient,
            OrderReconciliationService reconciliationService,
            OrderCancellationEventEmitter eventEmitter,
            OrderDispatchStateService dispatchStateService,
            TradeEntity trade,
            TradeOrderEntity order
    ) {
    }

    private static final class ImmediateTransactions implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    }
}
