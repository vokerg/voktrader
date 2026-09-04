package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
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
import org.junit.jupiter.api.Test;
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

class CancellationFillRaceTest {
    @Test
    void fillObservedWhileCancelIsInFlightWinsOverCancelledAndPreservesPosition() {
        TradeRepository tradeRepository = mock(TradeRepository.class);
        TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
        TradeFillRepository fillRepository = mock(TradeFillRepository.class);
        TradeEventRepository eventRepository = mock(TradeEventRepository.class);
        LiveExecutionService liveExecutionService = mock(LiveExecutionService.class);
        OrderLayerProperties properties = new OrderLayerProperties();
        List<TradeFillEntity> savedFills = new ArrayList<>();

        OrderReconciliationService reconciliationService = new OrderReconciliationService(
                tradeRepository,
                orderRepository,
                fillRepository,
                liveExecutionService,
                properties,
                new OrderCancellationEventEmitter(eventRepository, new ObjectMapper()),
                new OrderReconciliationEventEmitter(eventRepository, new ObjectMapper())
        );

        TradeIntent intent = entryIntent();
        TradeEntity trade = TradeEntity.fromIntent(intent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 1L);
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                1L,
                intent,
                ExecutionMode.LIVE,
                TradeVenue.POLYMARKET,
                "local-1"
        );
        ReflectionTestUtils.setField(order, "id", 2L);
        ReflectionTestUtils.setField(order, "requestedShares", new BigDecimal("5"));
        ReflectionTestUtils.setField(order, "remainingShares", new BigDecimal("5"));
        order.markSubmitted("remote-1", "{\"submitted\":true}");
        order.markCancelRequested("operator requested");
        order.applyFillState(
                TradeOrderStatus.CANCEL_RECONCILE,
                null,
                null,
                null,
                new BigDecimal("5"),
                null,
                false,
                null,
                null
        );

        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fillRepository.findByOrderId(2L)).thenAnswer(invocation -> List.copyOf(savedFills));
        when(fillRepository.findByRemoteFillId(any())).thenReturn(Optional.empty());
        when(fillRepository.findByRemoteFillKey(any())).thenReturn(Optional.empty());
        when(fillRepository.save(any(TradeFillEntity.class))).thenAnswer(invocation -> {
            TradeFillEntity fill = invocation.getArgument(0);
            savedFills.add(fill);
            return fill;
        });
        when(eventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.existsByTradeOrderIdAndEventType(any(), any())).thenReturn(false);
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(new ExecutorOrderStatusResponse(
                true,
                "remote-1",
                "CANCELLED",
                "market-id",
                "token-id",
                TradeSide.BUY,
                new BigDecimal("0.50"),
                new BigDecimal("5"),
                new BigDecimal("1"),
                BigDecimal.ZERO,
                new BigDecimal("0.50"),
                null,
                null,
                null,
                "{\"status\":\"CANCELLED\"}",
                null
        ));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorFillsResponse(
                        true,
                        List.of(new ExecutorFillResponse(
                                "remote-1",
                                "remote-trade-1",
                                "fill-1",
                                "token-id",
                                "market-id",
                                TradeSide.BUY,
                                new BigDecimal("0.50"),
                                new BigDecimal("1"),
                                new BigDecimal("0.01"),
                                LiquidityRole.MAKER,
                                Instant.parse("2026-08-10T18:40:00Z"),
                                "{\"fillId\":\"fill-1\"}"
                        )),
                        "{\"fills\":1}",
                        null
                ));

        reconciliationService.reconcileOrder(order, OrderReconciliationSource.POST_CANCEL);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED_DONE);
        assertThat(order.getFilledShares()).isEqualByComparingTo("1");
        assertThat(order.getRemainingShares()).isEqualByComparingTo("0");
        assertThat(order.getCancelReason()).isEqualTo("operator requested");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        assertThat(trade.getEntryFilledShares()).isEqualByComparingTo("1");
    }

    private TradeIntent entryIntent() {
        GammaMarketDto market = new GammaMarketDto(
                "market-id", "Question", "condition-id", "slug",
                Instant.parse("2026-08-10T20:00:00Z"),
                true, false, true, false, null, null, null, null
        );
        OutcomePrice price = new OutcomePrice(
                "token-id", "Up", new BigDecimal("0.49"), new BigDecimal("0.50"),
                new BigDecimal("0.01"), Instant.parse("2026-08-10T18:39:59Z")
        );
        return TradeIntent.buy(
                null,
                market,
                price,
                new BigDecimal("2.50"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.50"),
                "strategy",
                "rule",
                "entry"
        );
    }
}
