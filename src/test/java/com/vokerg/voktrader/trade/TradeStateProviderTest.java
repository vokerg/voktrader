package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeStateProviderTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final DbTradeStateProvider provider = new DbTradeStateProvider(tradeRepository, tradeOrderRepository);

    @Test
    void readsActiveTradeAndOrdersFromLocalPersistenceOnly() {
        TradeEntity trade = TradeEntity.fromIntent(intent(), ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 10L);
        trade.markEntryPending();
        TradeOrderEntity order = TradeOrderEntity.fromIntent(10L, intent(), ExecutionMode.LIVE, TradeVenue.POLYMARKET, "local-1");
        ReflectionTestUtils.setField(order, "id", 20L);
        order.markSubmitting("local-1", "{}");
        order.markSubmitted("remote-1", "{}");
        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                eq(7L), eq("strategy-test"), eq("market-id"), any()
        )).thenReturn(Optional.of(trade));
        when(tradeOrderRepository.findByTradeId(10L)).thenReturn(List.of(order));

        StrategyRuntimeState state = provider.getState(StrategyInstanceKey.of(7L, "strategy-test"), "market-id");

        assertThat(state.currentTradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        assertThat(state.activeEntryOrder()).isNotNull();
        assertThat(state.activeEntryOrder().localOrderId()).isEqualTo("local-1");
        assertThat(state.activeEntryOrder().remoteOrderId()).isEqualTo("remote-1");
        assertThat(state.activeEntryOrder().status()).isEqualTo(TradeOrderStatus.SUBMITTED);
        assertThat(state.feeKnown()).isFalse();
    }

    @Test
    void returnsNewStateWhenNoActiveTradeExists() {
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndStatusInOrderByUpdatedAtDesc(
                eq("strategy-test"), eq("market-id"), any()
        )).thenReturn(Optional.empty());

        StrategyRuntimeState state = provider.getState(StrategyInstanceKey.of(null, "strategy-test"), "market-id");

        assertThat(state.currentTradeStatus()).isEqualTo(TradeStatus.NEW);
        assertThat(state.hasPosition()).isFalse();
        assertThat(state.activeEntryOrder()).isNull();
    }

    private TradeIntent intent() {
        return new TradeIntent(
                7L,
                "strategy-test",
                "entry",
                "market-id",
                "slug",
                "Question",
                "condition-id",
                "token-up",
                "Up",
                TradeSide.BUY,
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                TradeOrderType.GTC,
                true,
                new BigDecimal("0.50"),
                new BigDecimal("0.49"),
                new BigDecimal("0.51"),
                new BigDecimal("0.02"),
                new BigDecimal("0.50"),
                Instant.parse("2026-05-09T12:00:00Z"),
                0L,
                Instant.parse("2026-05-09T12:00:00Z"),
                Instant.parse("2026-05-09T12:05:00Z"),
                300L,
                "test",
                null
        );
    }
}
