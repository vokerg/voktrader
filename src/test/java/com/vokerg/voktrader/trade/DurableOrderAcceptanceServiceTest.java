package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.outbox.AcceptedOrderIntent;
import com.vokerg.voktrader.trade.outbox.OrderDispatchState;
import com.vokerg.voktrader.trade.outbox.OrderIntentState;
import com.vokerg.voktrader.trade.outbox.TransactionalOrderIntentService;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableOrderAcceptanceServiceTest {
    private final TransactionalOrderIntentService intentService = mock(TransactionalOrderIntentService.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final LiveArmService liveArmService = mock(LiveArmService.class);
    private final ExecutorProperties executorProperties = new ExecutorProperties();
    private final DurableOrderAcceptanceService service = new DurableOrderAcceptanceService(
            intentService, tradeRepository, tradeOrderRepository, liveArmService, executorProperties
    );

    @BeforeEach
    void setUp() {
        executorProperties.setRequireImmediateFill(false);
        when(liveArmService.status()).thenReturn(armedStatus());
        when(tradeOrderRepository.findByClientOrderId("vok-client")).thenReturn(Optional.empty());
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
            TradeEntity trade = invocation.getArgument(0);
            if (trade.getId() == null) ReflectionTestUtils.setField(trade, "id", 11L);
            return trade;
        });
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> {
            TradeOrderEntity order = invocation.getArgument(0);
            if (order.getId() == null) ReflectionTestUtils.setField(order, "id", 22L);
            return order;
        });
    }

    @Test
    void acceptedEntryPersistsOutboxBeforeLinkedTradeAndOrder() {
        TradeIntent intent = entryIntent();
        when(intentService.accept(intent, ExecutionMode.LIVE, "risk-1")).thenReturn(accepted());

        OrderLifecycleResult result = service.accept(intent, ExecutionMode.LIVE, "risk-1");

        assertThat(result.success()).isTrue();
        assertThat(result.tradeId()).isEqualTo(11L);
        assertThat(result.orderId()).isEqualTo(22L);
        assertThat(result.localOrderId()).isEqualTo("vok-client");
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CREATED);

        InOrder ordering = inOrder(intentService, tradeRepository, tradeOrderRepository);
        ordering.verify(intentService).accept(intent, ExecutionMode.LIVE, "risk-1");
        ordering.verify(tradeRepository).save(any(TradeEntity.class));
        ordering.verify(tradeOrderRepository).save(any(TradeOrderEntity.class));
        ordering.verify(tradeRepository).save(any(TradeEntity.class));
    }

    @Test
    void replayReturnsExistingLinkedLifecycleWithoutCreatingDuplicateTrade() {
        TradeIntent intent = entryIntent();
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 11L);
        trade.markEntryPending();
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                11L, intent, ExecutionMode.LIVE,
                com.vokerg.voktrader.trade.model.TradeVenue.POLYMARKET, "vok-client"
        );
        ReflectionTestUtils.setField(order, "id", 22L);
        when(intentService.accept(intent, ExecutionMode.LIVE, "risk-1")).thenReturn(accepted());
        when(tradeOrderRepository.findByClientOrderId("vok-client")).thenReturn(Optional.of(order));
        when(tradeRepository.findById(11L)).thenReturn(Optional.of(trade));

        OrderLifecycleResult result = service.accept(intent, ExecutionMode.LIVE, "risk-1");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("replayed");
        verify(intentService).accept(intent, ExecutionMode.LIVE, "risk-1");
        org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(tradeOrderRepository, org.mockito.Mockito.never()).save(any());
    }

    private AcceptedOrderIntent accepted() {
        return new AcceptedOrderIntent(
                1L, 2L, "vok-client", "hash", "risk-1", ExecutionMode.LIVE,
                com.vokerg.voktrader.trade.model.TradeSide.BUY,
                OrderIntentState.ACCEPTED, OrderDispatchState.OUTBOX_READY,
                Instant.parse("2026-08-06T17:30:00Z")
        );
    }

    private TradeIntent entryIntent() {
        return TradeIntent.buy(
                null, market(), price(), new BigDecimal("1.00"),
                com.vokerg.voktrader.trade.model.TradeOrderType.FOK,
                false, new BigDecimal("0.51"), "strategy", "rule", "entry"
        );
    }

    private LiveArmService.LiveArmStatus armedStatus() {
        return new LiveArmService.LiveArmStatus(
                true,
                Instant.parse("2026-08-06T17:00:00Z"),
                Instant.parse("2026-08-06T18:00:00Z"),
                "0xexpected",
                true, true, true, true,
                List.of(), List.of()
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
                "token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"),
                new BigDecimal("0.02"), Instant.parse("2026-08-06T17:20:00Z")
        );
    }
}
