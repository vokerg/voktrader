package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
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
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.time.TimeMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
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

class OrderReconciliationServiceTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository tradeFillRepository = mock(TradeFillRepository.class);
    private final TradeEventRepository tradeEventRepository = mock(TradeEventRepository.class);
    private final LiveExecutionService liveExecutionService = mock(LiveExecutionService.class);
    private final OrderLayerProperties properties = new OrderLayerProperties();
    private final List<TradeFillEntity> savedFills = new ArrayList<>();
    private final List<TradeEventEntity> savedEvents = new ArrayList<>();
    private final OrderCancellationEventEmitter cancellationEventEmitter = new OrderCancellationEventEmitter(
            tradeEventRepository,
            new ObjectMapper()
    );
    private final OrderReconciliationEventEmitter reconciliationEventEmitter = new OrderReconciliationEventEmitter(
            tradeEventRepository,
            new ObjectMapper()
    );
    private final OrderReconciliationService service = new OrderReconciliationService(
            tradeRepository,
            tradeOrderRepository,
            tradeFillRepository,
            liveExecutionService,
            properties,
            cancellationEventEmitter,
            reconciliationEventEmitter
    );

    @BeforeEach
    void setUp() {
        savedFills.clear();
        savedEvents.clear();
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeFillRepository.save(any(TradeFillEntity.class))).thenAnswer(invocation -> {
            TradeFillEntity fill = invocation.getArgument(0);
            savedFills.add(fill);
            return fill;
        });
        when(tradeFillRepository.findByOrderId(any())).thenAnswer(invocation -> List.copyOf(savedFills));
        when(tradeFillRepository.findByRemoteFillId(any())).thenAnswer(invocation -> {
            String remoteFillId = invocation.getArgument(0);
            return savedFills.stream().filter(fill -> remoteFillId.equals(fill.getRemoteFillId())).findFirst();
        });
        when(tradeFillRepository.findByRemoteFillKey(any())).thenAnswer(invocation -> {
            String remoteFillKey = invocation.getArgument(0);
            return savedFills.stream().filter(fill -> remoteFillKey.equals(fill.getRemoteFillKey())).findFirst();
        });
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
    void remoteRestingKeepsEntryPending() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("OPEN"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.RESTING);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .contains(
                        OrderReconciliationEventEmitter.REQUESTED,
                        OrderReconciliationEventEmitter.PULLED,
                        OrderReconciliationEventEmitter.STATUS_CHANGED
                );
    }

    @Test
    void reconcileEmitsPulledEveryTimeAndNoChangeWhenStatusIsStable() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        order.markResting("{}");
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("OPEN"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);
        service.reconcileOrder(order);

        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .filteredOn(OrderReconciliationEventEmitter.PULLED::equals)
                .hasSize(2);
        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .contains(OrderReconciliationEventEmitter.NO_CHANGE);
    }

    @Test
    void partialEntryFillUpdatesOrderAndTrade() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("PARTIALLY_FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "2", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("2");
        assertThat(order.getRealizedFeeUsd()).isEqualByComparingTo("0.01");
        assertThat(order.getFeeKnown()).isTrue();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .contains(OrderReconciliationEventEmitter.FILL_IMPORTED);
    }

    @Test
    void duplicateRemoteFillIsNotDoubleCountedAcrossReconciliations() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("PARTIALLY_FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "2", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);
        service.reconcileOrder(order);

        assertThat(savedFills).hasSize(1);
        assertThat(order.getFilledShares()).isEqualByComparingTo("2");
    }

    @Test
    void missingRemoteFillIdUsesFallbackKeyForDedupe() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        ExecutorFillResponse fill = fill(null, "1", "0.50", "0.01");
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("PARTIALLY_FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorFillsResponse(true, List.of(fill), "{}", null));

        service.reconcileOrder(order);
        service.reconcileOrder(order);

        assertThat(savedFills).hasSize(1);
        assertThat(savedFills.getFirst().getRemoteFillKey()).isNotBlank();
    }

    @Test
    void fullEntryFillUpdatesTradeOpen() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "2", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(trade.getEntryFeeUsd()).isEqualByComparingTo("0.01");
    }

    @Test
    void unknownRemoteEntryOrderCanReconcileToFilledAndOpenTrade() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        order.markUnknown("{}");
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(new ExecutorOrderStatusResponse(
                true,
                "remote-1",
                "FILLED",
                "market-id",
                "token-id",
                TradeSide.BUY,
                new BigDecimal("0.57"),
                new BigDecimal("5"),
                new BigDecimal("5"),
                BigDecimal.ZERO,
                new BigDecimal("0.57"),
                null,
                Instant.parse("2026-05-12T20:46:36Z"),
                null,
                "{}",
                null
        ));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(
                true,
                List.of(fill("fill-1", "5", "0.57", "0")),
                "{}",
                null
        ));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("5");
        assertThat(order.getAvgFillPrice()).isEqualByComparingTo("0.57");
        assertThat(order.getRemainingShares()).isEqualByComparingTo("0");
        assertThat(order.getFillRole()).isEqualTo(LiquidityRole.MAKER);
        assertThat(order.getLastReconciledAt()).isNotNull();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(trade.getEntryFilledShares()).isEqualByComparingTo("5");
        assertThat(trade.getEntryAvgPrice()).isEqualByComparingTo("0.57");
        assertThat(trade.getEntryFilledUsd()).isEqualByComparingTo("2.85");
        assertThat(trade.getEntryFeeUsd()).isEqualByComparingTo("0");
        assertThat(trade.getEntryCompletedAt()).isNotNull();
    }

    @Test
    void reconcileOpenOrdersOnlyPollsOrdersWithRemoteOrderId() {
        TradeOrderEntity remoteOrder = order(trade(), TradeSide.BUY);
        when(tradeOrderRepository.findReconcilableRemoteOrders(any())).thenReturn(List.of(remoteOrder));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("OPEN"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        int reconciled = service.reconcileOpenOrders();

        assertThat(reconciled).isEqualTo(1);
        verify(tradeOrderRepository).findReconcilableRemoteOrders(any());
    }

    @Test
    void fillLookupUsesSafetyLookbackBeforeSubmittedAt() {
        properties.setFillLookupLookbackSeconds(300);
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        order.markSubmitting("local-1", "{}");
        order.markSubmitted("remote-1", "{}");
        Instant submittedAt = order.getSubmittedAt();
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("OPEN"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        verify(liveExecutionService).fetchRemoteFills(
                eq("remote-1"),
                eq("market-id"),
                eq("token-id"),
                eq(TradeSide.BUY),
                eq(new BigDecimal("0.50")),
                any(),
                eq(submittedAt.minusSeconds(300))
        );
    }

    @Test
    void filledRemoteStatusUsesOriginalSizeWhenFillRowsAreUnavailable() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(new ExecutorOrderStatusResponse(
                true,
                "remote-1",
                "FILLED",
                "market-id",
                "token-id",
                TradeSide.BUY,
                new BigDecimal("0.57"),
                new BigDecimal("5"),
                null,
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                "{}",
                null
        ));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("5");
        assertThat(order.getAvgFillPrice()).isEqualByComparingTo("0.57");
        assertThat(order.getRemainingShares()).isEqualByComparingTo("0");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(trade.getEntryFilledUsd()).isEqualByComparingTo("2.85");
    }

    @Test
    void remoteCancelledWithPartialFillsResolvesPartiallyFilled() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("CANCELLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "1", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
    }

    @Test
    void cancelledEntryWithNoFillCancelsTrade() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        ReflectionTestUtils.setField(order, "id", 6358L);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("CANCELLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .contains(OrderCancellationEventEmitter.CANCELLED_EVENT);
        TradeEventEntity cancelEvent = savedEvents.stream()
                .filter(event -> OrderCancellationEventEmitter.CANCELLED_EVENT.equals(event.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelEvent.getTradeOrderId()).isEqualTo(6358L);
        assertThat(cancelEvent.getPayloadJson())
                .contains("\"previousStatus\":\"SUBMITTED\"")
                .contains("\"resolvedStatus\":\"CANCELLED\"")
                .contains("\"cancelReason\":\"remote cancelled\"")
                .contains("\"remoteOrderId\":\"remote-1\"");
    }

    @Test
    void repeatedCancelledReconciliationDoesNotDuplicateCancelEvent() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        ReflectionTestUtils.setField(order, "id", 6358L);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("CANCELLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);
        service.reconcileOrder(order);

        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .containsOnlyOnce(OrderCancellationEventEmitter.CANCELLED_EVENT);
    }

    @Test
    void unknownRemoteStatusRemainsUnknownWhenThereAreNoFills() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("SOMETHING_NEW"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.UNKNOWN);
    }

    @Test
    void staleUnfilledGtdWithUnavailableRemoteStatusExpiresEntryTrade() {
        Instant now = Instant.parse("2026-05-13T12:00:00Z");
        TradeEntity[] tradeHolder = new TradeEntity[1];
        TradeOrderEntity[] orderHolder = new TradeOrderEntity[1];
        TimeMachine.runAt(now.minus(Duration.ofMinutes(121)), () -> {
            TradeIntent intent = TradeIntent.buy(null, market(), price(), new BigDecimal("1.00"), TradeOrderType.GTD, true, new BigDecimal("0.50"), "strategy", "rule", "entry");
            tradeHolder[0] = TradeEntity.fromIntent(intent, ExecutionMode.LIVE);
            orderHolder[0] = TradeOrderEntity.fromIntent(1L, intent, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1");
            orderHolder[0].markSubmitting("local-1", "{}");
            orderHolder[0].markSubmitted("remote-1", "{}");
        });
        TradeEntity trade = tradeHolder[0];
        TradeOrderEntity order = orderHolder[0];
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(
                ExecutorOrderStatusResponse.failure("remote-1", "EXCHANGE_REJECTION", "not found")
        );
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "[]", null));

        TimeMachine.runAt(now, () -> service.reconcileOrder(order));

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.EXPIRED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
    }

    @Test
    void remoteFillSizeIsUsedWhenFillRowsAreUnavailable() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(new ExecutorOrderStatusResponse(
                true,
                "remote-1",
                "PARTIALLY_FILLED",
                "market-id",
                "token-id",
                TradeSide.BUY,
                new BigDecimal("0.50"),
                new BigDecimal("2"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("0.50"),
                null,
                null,
                null,
                "{}",
                null
        ));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("1");
        assertThat(order.getFilledAmountUsd()).isEqualByComparingTo("0.50");
    }

    @Test
    void missingFeeStaysUnknown() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("PARTIALLY_FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "1", "0.50", null)), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getFeeKnown()).isFalse();
        assertThat(order.getRealizedFeeUsd()).isNull();
        assertThat(savedFills.getFirst().getFeeKnown()).isFalse();
        assertThat(savedFills.getFirst().getFeeUsd()).isNull();
    }

    private TradeEntity trade() {
        return TradeEntity.fromIntent(intent(TradeSide.BUY), ExecutionMode.LIVE);
    }

    private TradeOrderEntity order(TradeEntity trade, TradeSide side) {
        TradeOrderEntity order = TradeOrderEntity.fromIntent(1L, intent(side), ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1");
        order.markSubmitted("remote-1", "{}");
        return order;
    }

    private ExecutorOrderStatusResponse orderStatus(String status) {
        return new ExecutorOrderStatusResponse(true, "remote-1", status, "market-id", "token-id", TradeSide.BUY, new BigDecimal("0.50"), new BigDecimal("2"), null, null, null, null, null, null, "{}", null);
    }

    private ExecutorFillResponse fill(String fillId, String shares, String price, String fee) {
        return new ExecutorFillResponse("remote-1", null, fillId, "token-id", "market-id", TradeSide.BUY, new BigDecimal(price), new BigDecimal(shares), fee == null ? null : new BigDecimal(fee), LiquidityRole.MAKER, Instant.parse("2026-05-09T12:00:00Z"), "{}");
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
