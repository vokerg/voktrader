package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.outbox.OrderDispatchStateService;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelledExitTradeRecoveryTest {
    @Test
    void cancellingQueuedExitRestoresOpenPositionInsteadOfLeavingExitPending() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        PythonExecutorClient executorClient = mock(PythonExecutorClient.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        OrderCancellationEventEmitter eventEmitter = mock(OrderCancellationEventEmitter.class);
        OrderDispatchStateService dispatchStateService = mock(OrderDispatchStateService.class);

        GammaMarketDto market = market();
        OutcomePrice price = price();
        TradeIntent entry = TradeIntent.buy(
                market, price, new BigDecimal("1.00"), "strategy", "entry", "entry"
        );
        TradeEntity trade = TradeEntity.fromIntent(entry, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 11L);
        trade.markOpen(
                new BigDecimal("0.50"),
                new BigDecimal("2.00"),
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                Instant.parse("2026-08-11T03:00:00Z")
        );
        trade.markExitPending();

        TradeIntent exit = TradeIntent.sell(
                market, price, new BigDecimal("2.00"), "strategy", "exit", "operator exit"
        );
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                trade.getId(), exit, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "exit-local"
        );
        ReflectionTestUtils.setField(order, "id", 22L);

        when(orderRepository.findByLocalOrderId("exit-local")).thenReturn(Optional.of(order));
        when(orderRepository.findById(22L)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeRepository.findById(11L)).thenReturn(Optional.of(trade));
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dispatchStateService.cancelBeforeSubmission(
                eq("exit-local"), eq("operator requested"), any(Instant.class)
        )).thenReturn(true);

        DurableOrderCancellationService service = new DurableOrderCancellationService(
                tradeRepository,
                orderRepository,
                executorClient,
                reconciliationService,
                eventEmitter,
                dispatchStateService,
                new ImmediateTransactions(),
                Duration.ofSeconds(12)
        );

        OrderLifecycleResult result = service.cancel("exit-local", "operator requested");

        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        verify(executorClient, never()).cancelOrder(any());
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id", "Question", "condition-id", "slug",
                Instant.parse("2026-08-11T04:00:00Z"),
                true, false, true, false, null, null, null, null
        );
    }

    private OutcomePrice price() {
        return new OutcomePrice(
                "token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"),
                new BigDecimal("0.02"), Instant.parse("2026-08-11T02:59:59Z")
        );
    }

    private static final class ImmediateTransactions implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    }
}
