package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.outbox.TransactionalOrderIntentService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableOrderExitReplayTest {
    @Test
    void exactRetryReplaysPendingExitWhileDistinctExitRemainsBlocked() {
        TransactionalOrderIntentService intentService = mock(TransactionalOrderIntentService.class);
        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        ExecutorProperties executorProperties = new ExecutorProperties();
        executorProperties.setRequireImmediateFill(false);
        DurableOrderAcceptanceService service = new DurableOrderAcceptanceService(
                intentService, tradeRepository, orderRepository, mock(LiveArmService.class), executorProperties
        );

        TradeIntent entry = buyIntent();
        TradeEntity trade = TradeEntity.fromIntent(entry, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 11L);
        trade.markOpen(
                new BigDecimal("0.50"), new BigDecimal("2"), new BigDecimal("1.00"),
                BigDecimal.ZERO, Instant.parse("2026-08-06T17:00:00Z")
        );
        TradeIntent exit = sellIntent();
        TradeOrderEntity pendingOrder = TradeOrderEntity.fromIntent(
                11L, exit, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "vok-exit"
        );
        ReflectionTestUtils.setField(pendingOrder, "id", 22L);
        trade.markExitPending();

        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                exit.strategyId(),
                exit.marketId(),
                exit.tokenId(),
                List.of(TradeStatus.OPEN, TradeStatus.PARTIALLY_OPEN, TradeStatus.PARTIALLY_CLOSED, TradeStatus.EXIT_PENDING)
        )).thenReturn(Optional.of(trade));
        when(intentService.clientOrderIdFor(exit, "risk-exit")).thenReturn("vok-exit");
        when(orderRepository.findByClientOrderId("vok-exit")).thenReturn(Optional.of(pendingOrder));
        when(tradeRepository.findById(11L)).thenReturn(Optional.of(trade));

        OrderLifecycleResult replay = service.accept(exit, ExecutionMode.LIVE, "risk-exit");

        assertThat(replay.success()).isTrue();
        assertThat(replay.orderId()).isEqualTo(22L);
        assertThat(replay.message()).contains("replayed");
        verify(intentService, never()).accept(exit, ExecutionMode.LIVE, "risk-exit");

        when(intentService.clientOrderIdFor(exit, "risk-other")).thenReturn("vok-other");
        when(orderRepository.findByClientOrderId("vok-other")).thenReturn(Optional.empty());

        OrderLifecycleResult distinct = service.accept(exit, ExecutionMode.LIVE, "risk-other");

        assertThat(distinct.success()).isFalse();
        assertThat(distinct.tradeStatus()).isEqualTo(TradeStatus.EXIT_PENDING);
        assertThat(distinct.message()).contains("already pending");
        verify(intentService, never()).accept(exit, ExecutionMode.LIVE, "risk-other");
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
