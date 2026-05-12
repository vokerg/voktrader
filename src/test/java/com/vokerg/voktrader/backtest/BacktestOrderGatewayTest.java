package com.vokerg.voktrader.backtest;

import com.vokerg.voktrader.bot.BotRuntimeContext;
import com.vokerg.voktrader.bot.BotRuntimeContextHolder;
import com.vokerg.voktrader.market.TrackedMarketState;
import com.vokerg.voktrader.marketdata.LatestPriceState;
import com.vokerg.voktrader.marketdata.OrderBookState;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import com.vokerg.voktrader.time.TimeMachine;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.OrderLifecycleResult;
import com.vokerg.voktrader.trade.PolymarketFeeCalculator;
import com.vokerg.voktrader.trade.StrategyInstanceKey;
import com.vokerg.voktrader.trade.StrategyRuntimeState;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeFillEntity;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradeOrderEntity;
import com.vokerg.voktrader.trade.TradeOrderStatus;
import com.vokerg.voktrader.trade.TradeOrderType;
import com.vokerg.voktrader.trade.TradeSide;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

class BacktestOrderGatewayTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository orderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository fillRepository = mock(TradeFillRepository.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final BacktestExecutionProperties executionProperties = new BacktestExecutionProperties();
    private final Map<Long, TradeEntity> trades = new LinkedHashMap<>();
    private final Map<Long, TradeOrderEntity> orders = new LinkedHashMap<>();
    private long nextTradeId = 1;
    private long nextOrderId = 1;
    private BacktestOrderGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new BacktestOrderGateway(
                "run-1",
                tradeRepository,
                orderRepository,
                fillRepository,
                new PolymarketFeeCalculator(),
                tradingProperties,
                executionProperties
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
        when(tradeRepository.findByBacktestRunId("run-1")).thenAnswer(invocation -> new ArrayList<>(trades.values()));
    }

    @Test
    void fokFillsWhenDepthIsAvailable() {
        OrderLifecycleResult result = withBook("0.49", "10", "0.50", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.FOK, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.TESTING)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.OPEN);
    }

    @Test
    void fokDoesNotFillWhenDepthIsInsufficient() {
        OrderLifecycleResult result = withBook("0.49", "1", "0.50", "1", () ->
                gateway.submitOrder(intent(TradeOrderType.FOK, TradeSide.BUY, "0.50", "10.00", null), owner(), ExecutionMode.TESTING)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void fakPartiallyFillsAndLeavesTradePartiallyOpen() {
        OrderLifecycleResult result = withBook("0.49", "1", "0.50", "1", () ->
                gateway.submitOrder(intent(TradeOrderType.FAK, TradeSide.BUY, "0.50", "10.00", null), owner(), ExecutionMode.TESTING)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
    }

    @Test
    void gtdMakerNoFillRestsThenExpires() {
        executionProperties.setFillModel("maker_no_fill");
        executionProperties.setDefaultGtdSeconds(8);
        Instant start = Instant.parse("2026-05-09T12:00:00Z");
        TimeMachine.runAt(start, () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTD, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.TESTING)
        ));

        TimeMachine.runAt(start.plusSeconds(9), () -> withBook("0.49", "10", "0.51", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.EXPIRED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void makerTouchBuyFillsWhenFutureAskTouchesLimit() {
        executionProperties.setFillModel("maker_touch");
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.TESTING)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.50", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
    }

    @Test
    void makerTouchBuyPartiallyFillsAtTouchUsingConfiguredRatio() {
        executionProperties.setFillModel("maker_touch");
        executionProperties.setMakerTouchFillRatio(new BigDecimal("0.25"));
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "50.00", null), owner(), ExecutionMode.TESTING)
        ));

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.50", "40", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        TradeOrderEntity order = orders.values().iterator().next();
        TradeEntity trade = trades.values().iterator().next();
        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("10.00000000");
        assertThat(order.getRemainingShares()).isEqualByComparingTo("90.00000000");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:02Z"), () -> withBook("0.49", "10", "0.49", "40", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("100.00000000");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
    }

    @Test
    void makerCrossPessimisticBuyRequiresFutureAskBelowLimit() {
        executionProperties.setFillModel("maker_cross_pessimistic");
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.TESTING)
        ));
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.50", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));
        assertThat(orders.values().iterator().next().getStatus()).isEqualTo(TradeOrderStatus.RESTING);

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:02Z"), () -> withBook("0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));
        assertThat(orders.values().iterator().next().getStatus()).isEqualTo(TradeOrderStatus.FILLED);
    }

    @Test
    void cancelledOrderDoesNotFillLaterAndStateShowsNoActiveTrade() {
        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:00Z"), () -> withBook("0.49", "10", "0.51", "10", () ->
                gateway.submitOrder(intent(TradeOrderType.GTC, TradeSide.BUY, "0.50", "1.00", null), owner(), ExecutionMode.TESTING)
        ));
        String localOrderId = orders.values().iterator().next().getLocalOrderId();
        gateway.cancelOrder(localOrderId, "test cancel");

        TimeMachine.runAt(Instant.parse("2026-05-09T12:00:01Z"), () -> withBook("0.49", "10", "0.49", "10", () -> {
            gateway.advanceOpenOrders();
            return null;
        }));

        assertThat(orders.values().iterator().next().getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        StrategyRuntimeState state = gateway.getState(owner(), "market-id");
        assertThat(state.currentTradeStatus()).isEqualTo(TradeStatus.NEW);
    }

    private StrategyInstanceKey owner() {
        return StrategyInstanceKey.of(1L, "strategy-test");
    }

    private TradeIntent intent(TradeOrderType type, TradeSide side, String price, String amountUsd, String shares) {
        BigDecimal limit = new BigDecimal(price);
        return new TradeIntent(
                1L,
                "strategy-test",
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
                "test"
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
                "strategy-test",
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
