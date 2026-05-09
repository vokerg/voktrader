package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.executor.ExecutorFillResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderReconciliationServiceTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository tradeFillRepository = mock(TradeFillRepository.class);
    private final LiveExecutionService liveExecutionService = mock(LiveExecutionService.class);
    private final List<TradeFillEntity> savedFills = new ArrayList<>();
    private final OrderReconciliationService service = new OrderReconciliationService(
            tradeRepository,
            tradeOrderRepository,
            tradeFillRepository,
            liveExecutionService
    );

    @BeforeEach
    void setUp() {
        savedFills.clear();
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
    }

    @Test
    void remoteRestingKeepsEntryPending() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("OPEN"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.RESTING);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
    }

    @Test
    void partialEntryFillUpdatesOrderAndTrade() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("PARTIALLY_FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "2", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(order.getFilledShares()).isEqualByComparingTo("2");
        assertThat(order.getRealizedFeeUsd()).isEqualByComparingTo("0.01");
        assertThat(order.getFeeKnown()).isTrue();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
    }

    @Test
    void duplicateRemoteFillIsNotDoubleCountedAcrossReconciliations() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("PARTIALLY_FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "2", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);
        service.reconcileOrder(order);

        assertThat(savedFills).hasSize(1);
        assertThat(order.getFilledShares()).isEqualByComparingTo("2");
    }

    @Test
    void fullEntryFillUpdatesTradeOpen() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("FILLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "2", "0.50", "0.01")), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(trade.getEntryFeeUsd()).isEqualByComparingTo("0.01");
    }

    @Test
    void cancelledEntryWithNoFillCancelsTrade() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("CANCELLED"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void unknownRemoteStatusRemainsUnknownWhenThereAreNoFills() {
        TradeEntity trade = trade();
        TradeOrderEntity order = order(trade, TradeSide.BUY);
        when(tradeRepository.findById(1L)).thenReturn(Optional.of(trade));
        when(liveExecutionService.fetchRemoteOrderStatus("remote-1")).thenReturn(orderStatus("SOMETHING_NEW"));
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getStatus()).isEqualTo(TradeOrderStatus.UNKNOWN);
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
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(), "{}", null));

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
        when(liveExecutionService.fetchRemoteFills(any(), any(), any(), any())).thenReturn(new ExecutorFillsResponse(true, List.of(fill("fill-1", "1", "0.50", null)), "{}", null));

        service.reconcileOrder(order);

        assertThat(order.getFeeKnown()).isFalse();
        assertThat(order.getRealizedFeeUsd()).isNull();
        assertThat(savedFills.getFirst().getFeeKnown()).isFalse();
        assertThat(savedFills.getFirst().getFeeUsd()).isNull();
    }

    private TradeEntity trade() {
        return TradeEntity.fromIntent(intent(TradeSide.BUY), ExecutionMode.LIVE_TINY);
    }

    private TradeOrderEntity order(TradeEntity trade, TradeSide side) {
        TradeOrderEntity order = TradeOrderEntity.fromIntent(1L, intent(side), ExecutionMode.LIVE_TINY, TradeVenue.POLYMARKET, "local-1");
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
