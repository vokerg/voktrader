package com.vokerg.voktrader.trade.paper;

import com.vokerg.voktrader.bot.BotRuntimeContext;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.OrderCancellationEventEmitter;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeExecutionSafetyService;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.simulation.BookOrderFillSimulator;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperOrderGatewayTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository fillRepository = mock(TradeFillRepository.class);
    private final TradeEventRepository eventRepository = mock(TradeEventRepository.class);
    private final TradingEventLogger eventLogger = mock(TradingEventLogger.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final PaperExecutionProperties executionProperties = new PaperExecutionProperties();
    private final Map<Long, TradeEntity> trades = new LinkedHashMap<>();
    private final Map<Long, TradeOrderEntity> orders = new LinkedHashMap<>();
    private final List<TradeEventEntity> savedEvents = new ArrayList<>();
    private long nextTradeId = 1;
    private long nextOrderId = 1;
    private PaperOrderGateway gateway;

    @BeforeEach
    void setUp() {
        savedEvents.clear();
        gateway = newGateway();
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
            TradeEntity trade = invocation.getArgument(0);
            if (trade.getId() == null) {
                ReflectionTestUtils.setField(trade, "id", nextTradeId++);
            }
            trades.put(trade.getId(), trade);
            return trade;
        });
        when(orderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> {
            TradeOrderEntity order = invocation.getArgument(0);
            if (order.getId() == null) {
                ReflectionTestUtils.setField(order, "id", nextOrderId++);
            }
            orders.put(order.getId(), order);
            return order;
        });
        when(fillRepository.save(any(TradeFillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> {
            TradeEventEntity event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });
        when(eventRepository.existsByTradeOrderIdAndEventType(any(), any())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            String eventType = invocation.getArgument(1);
            return savedEvents.stream().anyMatch(event -> orderId.equals(event.getTradeOrderId())
                    && eventType.equals(event.getEventType()));
        });
        when(tradeRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(trades.get(invocation.getArgument(0))));
        when(orderRepository.findByTradeId(any())).thenAnswer(invocation -> orders.values().stream()
                .filter(order -> invocation.getArgument(0).equals(order.getTradeId()))
                .toList());
        when(orderRepository.findByLocalOrderId(any())).thenAnswer(invocation -> orders.values().stream()
                .filter(order -> invocation.getArgument(0).equals(order.getLocalOrderId()))
                .findFirst());
        when(orderRepository.findByClientOrderId(any())).thenAnswer(invocation -> orders.values().stream()
                .filter(order -> invocation.getArgument(0).equals(order.getClientOrderId()))
                .findFirst());
        when(orderRepository.findByRemoteOrderId(any())).thenReturn(Optional.empty());
        when(orderRepository.findByModeAndVenueAndStatusInOrderByUpdatedAtAsc(any(), any(), any())).thenAnswer(invocation -> {
            ExecutionMode mode = invocation.getArgument(0);
            var venue = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            List<TradeOrderStatus> statuses = invocation.getArgument(2);
            return orders.values().stream()
                    .filter(order -> order.getMode() == mode)
                    .filter(order -> order.getVenue() == venue)
                    .filter(order -> statuses.contains(order.getStatus()))
                    .sorted(java.util.Comparator.comparing(TradeOrderEntity::getUpdatedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                    .toList();
        });
        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByUpdatedAtDesc(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> trades.values().stream()
                        .filter(trade -> invocation.getArgument(0).equals(trade.getBotId()))
                        .filter(trade -> invocation.getArgument(1).equals(trade.getStrategyId()))
                        .filter(trade -> invocation.getArgument(2).equals(trade.getMarketId()))
                        .filter(trade -> invocation.getArgument(3).equals(trade.getTokenId()))
                        .filter(trade -> ((List<TradeStatus>) invocation.getArgument(4)).contains(trade.getStatus()))
                        .max(java.util.Comparator.comparing(TradeEntity::getUpdatedAt)));
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(any(), any(), any()))
                .thenAnswer(invocation -> trades.values().stream()
                        .filter(trade -> invocation.getArgument(0).equals(trade.getStrategyId()))
                        .filter(trade -> invocation.getArgument(1).equals(trade.getMarketId()))
                        .filter(trade -> ((List<TradeStatus>) invocation.getArgument(2)).contains(trade.getStatus()))
                        .max(java.util.Comparator.comparing(TradeEntity::getUpdatedAt)));
    }

    @Test
    void restingOrderAdvancesAfterGatewayRecreation() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        gateway = newGateway();

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
    }

    @Test
    void gtcBuyRestsWhenAskStaysAboveLimit() {
        OrderLifecycleResult result = withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.RESTING);
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
    }

    @Test
    void gtcBuyFillsWhenFutureAskCrossesBelowLimit() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
    }

    @Test
    void makerTouchPartiallyFillsAtTouch() {
        executionProperties.setFillModel("maker_touch");
        executionProperties.setMakerTouchFillRatio(new BigDecimal("0.25"));
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "50.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.50", "40", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("10.00000000");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
    }

    @Test
    void gtdExpiresAfterConfiguredTimeout() {
        executionProperties.setFillModel("maker_no_fill");
        executionProperties.setDefaultGtdSeconds(2);
        Instant start = Instant.parse("2026-05-09T12:00:00Z");
        TimeMachine.runAt(start, () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTD, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        gateway = newGateway();

        TimeMachine.runAt(start.plusSeconds(3), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.EXPIRED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void cancelledOrderDoesNotFillLater() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));
        String localOrderId = orders.values().iterator().next().getLocalOrderId();
        gateway.cancelOrder(localOrderId, "test cancel");

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void cancelRestingPaperOrderPersistsPaperCancellationEvents() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTD, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        String localOrderId = orders.values().iterator().next().getLocalOrderId();
        gateway.cancelOrder(localOrderId, "manual test cancel");

        assertThat(savedEvents)
                .extracting(TradeEventEntity::getEventType)
                .contains(OrderCancellationEventEmitter.PAPER_CANCEL_REQUESTED_EVENT,
                        OrderCancellationEventEmitter.PAPER_CANCELLED_EVENT);
        assertThat(savedEvents.stream()
                .filter(event -> OrderCancellationEventEmitter.PAPER_CANCEL_REQUESTED_EVENT.equals(event.getEventType())
                        || OrderCancellationEventEmitter.PAPER_CANCELLED_EVENT.equals(event.getEventType()))
                .map(TradeEventEntity::getPayloadJson))
                .allMatch(payload -> payload != null && payload.contains("\"cancelReason\":\"manual test cancel\""));
    }

    @Test
    void fokAndFakStillUseImmediateTakerBehavior() {
        OrderLifecycleResult fok = withBook(1L, "market-id", "0.49", "2", "0.50", "2", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.FOK, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        );
        OrderLifecycleResult fak = withBook(1L, "market-id", "0.49", "1", "0.50", "1", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.FAK, TradeSide.BUY, "0.50", "10.00", null), owner(1L), ExecutionMode.PAPER)
        );

        assertThat(fok.orderStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(fak.orderStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void differentBotOrderIsNotAdvancedUnderCurrentBotContext() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(2L, "market-id", "0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        assertThat(orders.values().iterator().next().getStatus()).isEqualTo(TradeOrderStatus.RESTING);
    }

    @Test
    void matchingBotOrderAdvances() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-id", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(1L, "market-id", "0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        assertThat(orders.values().iterator().next().getStatus()).isEqualTo(TradeOrderStatus.FILLED);
    }

    @Test
    void differentMarketOrderIsNotAdvanced() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook(1L, "market-a", "0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(1L, "market-a", TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(1L), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook(1L, "market-b", "0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        assertThat(orders.values().iterator().next().getStatus()).isEqualTo(TradeOrderStatus.RESTING);
    }

    private PaperOrderGateway newGateway() {
        return new PaperOrderGateway(
                tradeRepository,
                orderRepository,
                fillRepository,
                new PolymarketFeeCalculator(),
                tradingProperties,
                executionProperties,
                new BookOrderFillSimulator(),
                new TradeExecutionSafetyService(orderRepository, eventRepository, eventLogger, new ObjectMapper()),
                new OrderCancellationEventEmitter(eventRepository, new ObjectMapper())
        );
    }

    private StrategyInstanceKey owner(Long botId) {
        return StrategyInstanceKey.of(botId, "MK_GTD_EDGE_A");
    }

    private TradeIntent intent(Long botId, String marketId, TradeOrderType type, TradeSide side, String price, String amountUsd, String shares) {
        BigDecimal limit = new BigDecimal(price);
        return new TradeIntent(
                botId,
                "MK_GTD_EDGE_A",
                side == TradeSide.BUY ? "entry" : "exit",
                marketId,
                "slug",
                "Question",
                "condition-id",
                "token-up",
                "Up",
                side,
                amountUsd == null ? null : new BigDecimal(amountUsd),
                shares == null ? null : new BigDecimal(shares),
                type,
                type.canRestOnBook(),
                limit,
                new BigDecimal("0.49"),
                new BigDecimal("0.51"),
                new BigDecimal("0.02"),
                new BigDecimal("0.50"),
                TimeMachine.now(),
                0L,
                TimeMachine.now(),
                TimeMachine.now().plusSeconds(60),
                60L,
                "test",
                null
        );
    }

    private <T> T withBook(
            Long botId,
            String marketId,
            String bid,
            String bidSize,
            String ask,
            String askSize,
            java.util.concurrent.Callable<T> callable
    ) {
        OrderBookState orderBookState = new OrderBookState();
        orderBookState.update(
                "token-up",
                "Up",
                List.of(new PriceLevelDto(bid, bidSize)),
                List.of(new PriceLevelDto(ask, askSize)),
                TimeMachine.now()
        );
        TrackedMarketState trackedMarketState = new TrackedMarketState();
        trackedMarketState.setCurrentMarket(new com.vokerg.voktrader.polymarket.dto.GammaMarketDto(
                marketId,
                "Question",
                "condition-id",
                "slug",
                TimeMachine.now().plusSeconds(60),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        ));
        BotRuntimeContext context = new BotRuntimeContext(
                botId,
                null,
                "strategy-v2",
                null,
                null,
                trackedMarketState,
                new LatestPriceState(),
                orderBookState
        );
        final Object[] result = new Object[1];
        BotRuntimeContextHolder.runWith(context, () -> {
            try {
                result[0] = callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }
}
