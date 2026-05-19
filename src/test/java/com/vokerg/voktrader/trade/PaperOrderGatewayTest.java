package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.bot.BotRuntimeContext;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import com.vokerg.voktrader.time.TimeMachine;
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
    private long nextTradeId = 1;
    private long nextOrderId = 1;
    private PaperOrderGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new PaperOrderGateway(
                tradeRepository,
                orderRepository,
                fillRepository,
                new PolymarketFeeCalculator(),
                tradingProperties,
                executionProperties,
                new BookOrderFillSimulator(),
                new TradeExecutionSafetyService(orderRepository, eventRepository, eventLogger, new ObjectMapper())
        );
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
        when(eventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
    void gtcBuyRestsWhenAskStaysAboveLimit() {
        OrderLifecycleResult result = withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.PAPER)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.RESTING);
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
    }

    @Test
    void gtcBuyFillsWhenFutureAskCrossesBelowLimit() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.49", "10", () -> {
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
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "50.00", null), owner(), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.50", "40", () -> {
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
        TimeMachine.runAt(start, () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTD, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.PAPER)
        ));

        TimeMachine.runAt(start.plusSeconds(3), () -> withBook("0.49", "10", "0.51", "10", () -> {
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
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.PAPER)
        ));
        String localOrderId = orders.values().iterator().next().getLocalOrderId();
        gateway.cancelOrder(localOrderId, "test cancel");

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void fokAndFakStillUseImmediateTakerBehavior() {
        OrderLifecycleResult fok = withBook("0.49", "2", "0.50", "2", () ->
                gateway.submitOrder(intent(TradeOrderType.FOK, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.PAPER)
        );
        OrderLifecycleResult fak = withBook("0.49", "1", "0.50", "1", () ->
                gateway.submitOrder(intent(TradeOrderType.FAK, TradeSide.BUY, "0.50", "10.00", null), owner(), ExecutionMode.PAPER)
        );

        assertThat(fok.orderStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(fak.orderStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
    }

    private StrategyInstanceKey owner() {
        return StrategyInstanceKey.of(1L, "MK_GTD_EDGE_A");
    }

    private TradeIntent intent(TradeOrderType type, TradeSide side, String price, String amountUsd, String shares) {
        BigDecimal limit = new BigDecimal(price);
        return new TradeIntent(
                1L,
                "MK_GTD_EDGE_A",
                side == TradeSide.BUY ? "entry" : "exit",
                "market-id",
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

    private <T> T withBook(String bid, String bidSize, String ask, String askSize, java.util.concurrent.Callable<T> callable) {
        OrderBookState orderBookState = new OrderBookState();
        orderBookState.update(
                "token-up",
                "Up",
                List.of(new PriceLevelDto(bid, bidSize)),
                List.of(new PriceLevelDto(ask, askSize)),
                TimeMachine.now()
        );
        BotRuntimeContext context = new BotRuntimeContext(
                1L,
                null,
                "strategy-v2",
                null,
                null,
                new TrackedMarketState(),
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
