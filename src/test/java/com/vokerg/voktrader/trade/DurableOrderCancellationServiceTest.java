package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.OrderReconciliationSource;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableOrderCancellationServiceTest {
    @Test
    void cancelRequestCommitsBeforeRemoteCallAndResponsePersistence() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        PythonExecutorClient executorClient = mock(PythonExecutorClient.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        OrderCancellationEventEmitter eventEmitter = mock(OrderCancellationEventEmitter.class);
        TransactionOperations transactions = new ImmediateTransactions();
        DurableOrderCancellationService service = new DurableOrderCancellationService(
                tradeRepository,
                orderRepository,
                executorClient,
                reconciliationService,
                eventEmitter,
                transactions
        );

        TradeIntent intent = entryIntent();
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 11L);
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                11L, intent, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1"
        );
        ReflectionTestUtils.setField(order, "id", 22L);
        order.markSubmitted("remote-1", "{}");
        when(orderRepository.findByLocalOrderId("local-1")).thenReturn(Optional.of(order));
        when(orderRepository.findById(22L)).thenReturn(Optional.of(order));
        when(tradeRepository.findById(11L)).thenReturn(Optional.of(trade));
        when(orderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ExecutorCancelOrderResponse response = new ExecutorCancelOrderResponse(
                true, "remote-1", "CANCELLED", "{\"status\":\"CANCELLED\"}", null
        );
        when(executorClient.cancelOrder("remote-1")).thenReturn(response);
        OrderLifecycleResult reconciled = new OrderLifecycleResult(
                true, 11L, 22L, "local-1", "remote-1", trade.getStatus(), order.getStatus(), "CANCELLED", null
        );
        when(reconciliationService.reconcileOrder("local-1", OrderReconciliationSource.POST_CANCEL))
                .thenReturn(reconciled);

        OrderLifecycleResult result = service.cancel("local-1", "operator requested");

        assertThat(result).isSameAs(reconciled);
        assertThat(order.getCancelReason()).isEqualTo("operator requested");
        InOrder ordering = inOrder(orderRepository, eventEmitter, executorClient, reconciliationService);
        ordering.verify(orderRepository).save(order);
        ordering.verify(eventEmitter).emitCancelRequested(trade, order, "operator requested", null);
        ordering.verify(executorClient).cancelOrder("remote-1");
        ordering.verify(orderRepository).findById(22L);
        ordering.verify(orderRepository).save(order);
        ordering.verify(reconciliationService).reconcileOrder("local-1", OrderReconciliationSource.POST_CANCEL);
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

    private static final class ImmediateTransactions implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    }
}
