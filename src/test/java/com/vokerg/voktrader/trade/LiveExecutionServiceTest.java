package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOpenOrdersResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveExecutionServiceTest {
    private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
    private final TradeRiskCheckRepository riskCheckRepository = mock(TradeRiskCheckRepository.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository tradeFillRepository = mock(TradeFillRepository.class);
    private final TradeEventRepository tradeEventRepository = mock(TradeEventRepository.class);
    private final PythonExecutorClient pythonExecutorClient = mock(PythonExecutorClient.class);
    private final ExecutorProperties executorProperties = new ExecutorProperties();
    private final TradingProperties tradingProperties = new TradingProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LiveExecutionService service = new LiveExecutionService(
            riskCheckService,
            riskCheckRepository,
            tradeRepository,
            tradeOrderRepository,
            tradeFillRepository,
            tradeEventRepository,
            pythonExecutorClient,
            executorProperties,
            tradingProperties,
            new PolymarketFeeCalculator(),
            mock(TradingEventLogger.class),
            objectMapper
    );

    @BeforeEach
    void setUp() {
        RiskAssessment passed = new RiskAssessment();
        when(riskCheckService.assess(any(), any(), any(), any(), any())).thenReturn(passed);
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeFillRepository.save(any(TradeFillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeEventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void filledBuyEstimatesMissingExecutorFee() {
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(response(
                new BigDecimal("0.60"),
                new BigDecimal("1.666665"),
                new BigDecimal("0.999999"),
                null
        ));

        service.execute(TradeIntent.buy(
                market(),
                price("down", "Down", "0.59", "0.60"),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        ), ExecutionMode.LIVE);

        ArgumentCaptor<TradeEntity> tradeCaptor = ArgumentCaptor.forClass(TradeEntity.class);
        ArgumentCaptor<TradeFillEntity> fillCaptor = ArgumentCaptor.forClass(TradeFillEntity.class);
        org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.atLeastOnce()).save(tradeCaptor.capture());
        org.mockito.Mockito.verify(tradeFillRepository).save(fillCaptor.capture());

        TradeEntity savedTrade = tradeCaptor.getAllValues().getLast();
        assertThat(savedTrade.getEntryFeeUsd()).isEqualByComparingTo("0.02879997");
        assertThat(savedTrade.getTotalFeeUsd()).isEqualByComparingTo("0.02879997");
        assertThat(fillCaptor.getValue().getFeeUsd()).isEqualByComparingTo("0.02879997");
    }

    @Test
    void filledSellEstimatesMissingExecutorFeeAndPersistsNetPnl() {
        TradeEntity open = TradeEntity.fromIntent(TradeIntent.buy(
                market(),
                price("down", "Down", "0.57", "0.57999983"),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        ), ExecutionMode.LIVE);
        open.markOpen(
                new BigDecimal("0.57999983"),
                new BigDecimal("1.724135"),
                new BigDecimal("0.999998"),
                new BigDecimal("0.03023995"),
                Instant.parse("2026-04-30T10:00:00Z")
        );
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                "cost-aware-momentum",
                "market-id",
                "down",
                TradePositionSupport.EXITABLE_STATUSES
        )).thenReturn(Optional.of(open));
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(response(
                new BigDecimal("0.53"),
                new BigDecimal("1.724135"),
                new BigDecimal("0.91379155"),
                null
        ));

        service.execute(TradeIntent.sell(
                market(),
                price("down", "Down", "0.53", "0.54"),
                new BigDecimal("1.724135"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "exit"
        ), ExecutionMode.LIVE);

        assertThat(open.getExitFeeUsd()).isEqualByComparingTo("0.03092271");
        assertThat(open.getTotalFeeUsd()).isEqualByComparingTo("0.06116266");
        assertThat(open.getFinalPnlUsd()).isEqualByComparingTo("-0.14736911");
    }

    @Test
    void bookPressureExitForLiveTinyTradeUsesExecutorAndNotPaperCloseEvents() {
        TradeIntent entryIntent = TradeIntent.buy(
                67L,
                market(),
                price("up", "Up", "0.54", "0.55"),
                new BigDecimal("2.75"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.55"),
                "MK_GTD_EDGE_A",
                "mk-gtd-edge-a-entry",
                "entry"
        );
        TradeEntity open = TradeEntity.fromIntent(entryIntent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(open, "id", 5306L);
        open.markOpen(
                new BigDecimal("0.55"),
                new BigDecimal("5"),
                new BigDecimal("2.75"),
                BigDecimal.ZERO,
                Instant.parse("2026-05-13T18:26:46Z")
        );
        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_A",
                "market-id",
                "up",
                TradePositionSupport.EXITABLE_STATUSES
        )).thenReturn(Optional.of(open));
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "0x-live-exit",
                new BigDecimal("0.52"),
                new BigDecimal("5"),
                new BigDecimal("2.60"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-13T18:27:01Z")
        ));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                67L,
                market(),
                price("up", "Up", "0.52", "0.53"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.52"),
                "MK_GTD_EDGE_A",
                "book-pressure-flips",
                "strategy-v2 exit strategy=MK_GTD_EDGE_A rule=book-pressure-flips outcome=Up"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isTrue();
        assertThat(result.message()).isEqualTo("live exit filled");
        verify(pythonExecutorClient).submit(any(ExecutorOrderCommand.class));

        ArgumentCaptor<TradeOrderEntity> orderCaptor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(tradeOrderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        TradeOrderEntity exitOrder = orderCaptor.getAllValues().getLast();
        assertThat(exitOrder.getPhase()).isEqualTo(TradeOrderPhase.EXIT);
        assertThat(exitOrder.getMode()).isEqualTo(ExecutionMode.LIVE);
        assertThat(exitOrder.getVenue()).isEqualTo(TradeVenue.POLYMARKET);
        assertThat(exitOrder.getRemoteOrderId()).isEqualTo("0x-live-exit");
        assertThat(exitOrder.getSubmittedAt()).isNotNull();

        ArgumentCaptor<TradeEventEntity> eventCaptor = ArgumentCaptor.forClass(TradeEventEntity.class);
        verify(tradeEventRepository, org.mockito.Mockito.atLeastOnce()).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(TradeEventEntity::getEventType)
                .contains("EXIT_ORDER_CREATED", "LIVE_EXIT_FILLED")
                .doesNotContain("EXIT_FILLED", "CLOSED");
    }

    @Test
    void liveExitCanClosePartiallyOpenTrade() {
        TradeIntent entryIntent = TradeIntent.buy(
                market(),
                price("down", "Down", "0.55", "0.56"),
                new BigDecimal("2.55"),
                "strategy",
                "rule",
                "entry"
        );
        TradeEntity trade = TradeEntity.fromIntent(entryIntent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 77L);
        trade.markPartiallyOpen(
                new BigDecimal("0.56"),
                new BigDecimal("4.545453"),
                new BigDecimal("2.54545368"),
                BigDecimal.ZERO,
                Instant.parse("2026-05-23T07:00:00Z")
        );
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                "strategy",
                "market-id",
                "down",
                TradePositionSupport.EXITABLE_STATUSES
        )).thenReturn(Optional.of(trade));
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "exit-1",
                new BigDecimal("0.52"),
                new BigDecimal("4.545453"),
                new BigDecimal("2.36363556"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T07:00:05Z")
        ));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market(),
                price("down", "Down", "0.52", "0.53"),
                new BigDecimal("4.545453"),
                "strategy",
                "rule",
                "exit"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isTrue();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(trade.getExitFilledShares()).isEqualByComparingTo("4.545453");
        assertThat(trade.getFinalPnlUsd()).isNotNull();

        ArgumentCaptor<TradeEntity> tradeCaptor = ArgumentCaptor.forClass(TradeEntity.class);
        verify(tradeRepository, org.mockito.Mockito.never()).save(argThat(saved -> saved != trade));
        verify(tradeRepository, org.mockito.Mockito.atLeastOnce()).save(tradeCaptor.capture());
        assertThat(tradeCaptor.getAllValues()).allMatch(saved -> saved == trade);

        ArgumentCaptor<TradeOrderEntity> orderCaptor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(tradeOrderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        TradeOrderEntity exitOrder = orderCaptor.getAllValues().getLast();
        assertThat(exitOrder.getTradeId()).isEqualTo(77L);
        assertThat(exitOrder.getRequestedShares()).isEqualByComparingTo("4.545453");
    }

    @Test
    void liveExitPartialCloseKeepsPartiallyClosed() {
        TradeEntity trade = TradeEntity.fromIntent(TradeIntent.buy(
                market(),
                price("down", "Down", "0.55", "0.56"),
                new BigDecimal("2.52"),
                "strategy",
                "rule",
                "entry"
        ), ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 78L);
        trade.markPartiallyOpen(
                new BigDecimal("0.56"),
                new BigDecimal("4.5"),
                new BigDecimal("2.52"),
                BigDecimal.ZERO,
                Instant.parse("2026-05-23T07:00:00Z")
        );
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                "strategy",
                "market-id",
                "down",
                TradePositionSupport.EXITABLE_STATUSES
        )).thenReturn(Optional.of(trade));
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "exit-2",
                new BigDecimal("0.51"),
                new BigDecimal("2.0"),
                new BigDecimal("1.02"),
                BigDecimal.ZERO,
                "matched",
                "{}",
                Instant.parse("2026-05-23T07:00:05Z")
        ));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market(),
                price("down", "Down", "0.51", "0.52"),
                new BigDecimal("2.0"),
                "strategy",
                "rule",
                "exit"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isTrue();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_CLOSED);
        assertThat(trade.getExitFilledShares()).isEqualByComparingTo("2.0");
        assertThat(TradePositionSupport.heldShares(trade)).isEqualByComparingTo("2.5");
    }

    @Test
    void liveExitRejectsWhenNoHeldShares() {
        TradeEntity trade = TradeEntity.fromIntent(TradeIntent.buy(
                market(),
                price("down", "Down", "0.55", "0.56"),
                new BigDecimal("2.52"),
                "strategy",
                "rule",
                "entry"
        ), ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 79L);
        ReflectionTestUtils.setField(trade, "status", TradeStatus.PARTIALLY_OPEN);
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                "strategy",
                "market-id",
                "down",
                TradePositionSupport.EXITABLE_STATUSES
        )).thenReturn(Optional.of(trade));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market(),
                price("down", "Down", "0.51", "0.52"),
                new BigDecimal("1.0"),
                "strategy",
                "rule",
                "exit"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).contains("no held shares");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);
        verify(pythonExecutorClient, never()).submit(any(ExecutorOrderCommand.class));
    }

    @Test
    void liveExitRejectPreservesPartialPosition() {
        TradeEntity trade = TradeEntity.fromIntent(TradeIntent.buy(
                market(),
                price("down", "Down", "0.55", "0.56"),
                new BigDecimal("2.52"),
                "strategy",
                "rule",
                "entry"
        ), ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 80L);
        trade.markPartiallyOpen(
                new BigDecimal("0.56"),
                new BigDecimal("4.5"),
                new BigDecimal("2.52"),
                BigDecimal.ZERO,
                Instant.parse("2026-05-23T07:00:00Z")
        );
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                "strategy",
                "market-id",
                "down",
                TradePositionSupport.EXITABLE_STATUSES
        )).thenReturn(Optional.of(trade));
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(
                ExecutorOrderResponse.rejected("exchange rejected")
        );

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market(),
                price("down", "Down", "0.51", "0.52"),
                new BigDecimal("4.5"),
                "strategy",
                "rule",
                "exit"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isFalse();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PARTIALLY_OPEN);

        ArgumentCaptor<TradeOrderEntity> orderCaptor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(tradeOrderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertThat(orderCaptor.getAllValues().getLast().getStatus()).isEqualTo(com.vokerg.voktrader.trade.model.TradeOrderStatus.REJECTED);
    }

    @Test
    void explicitExecutorZeroFeeIsPreserved() {
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(response(
                new BigDecimal("0.60"),
                new BigDecimal("1.666665"),
                new BigDecimal("0.999999"),
                BigDecimal.ZERO
        ));

        service.execute(TradeIntent.buy(
                market(),
                price("down", "Down", "0.59", "0.60"),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        ), ExecutionMode.LIVE);

        ArgumentCaptor<TradeEntity> tradeCaptor = ArgumentCaptor.forClass(TradeEntity.class);
        org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.atLeastOnce()).save(tradeCaptor.capture());
        assertThat(tradeCaptor.getAllValues().getLast().getEntryFeeUsd()).isEqualByComparingTo("0");
    }

    @Test
    void missingExecutorFeeCanBeStoredAsZeroWhenEstimationDisabled() {
        tradingProperties.setEstimateLiveFeesWhenMissing(false);
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(response(
                new BigDecimal("0.60"),
                new BigDecimal("1.666665"),
                new BigDecimal("0.999999"),
                null
        ));

        service.execute(TradeIntent.buy(
                market(),
                price("down", "Down", "0.59", "0.60"),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        ), ExecutionMode.LIVE);

        ArgumentCaptor<TradeEntity> tradeCaptor = ArgumentCaptor.forClass(TradeEntity.class);
        org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.atLeastOnce()).save(tradeCaptor.capture());
        assertThat(tradeCaptor.getAllValues().getLast().getEntryFeeUsd()).isEqualByComparingTo("0");
    }

    @Test
    void makerBuyIsRejectedBeforeExecutorWhenImmediateFillRequired() {
        TradeExecutionResult result = service.execute(TradeIntent.buyMaker(
                market(),
                price("down", "Down", "0.59", "0.60"),
                new BigDecimal("1.00"),
                "maker-resolution-carry",
                "maker-resolution-carry",
                "entry"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).contains("require-immediate-fill=true");
        org.mockito.Mockito.verify(pythonExecutorClient, never()).submit(any(ExecutorOrderCommand.class));
    }

    @Test
    void makerBuyCanReachExecutorWhenImmediateFillIsDisabled() {
        executorProperties.setRequireImmediateFill(false);
        when(pythonExecutorClient.submit(any(ExecutorOrderCommand.class))).thenReturn(new ExecutorOrderResponse(
                true,
                false,
                "SUBMITTED",
                "exchange-order",
                new BigDecimal("0.59"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "submitted",
                "{}",
                Instant.now()
        ));

        TradeExecutionResult result = service.execute(TradeIntent.buyMaker(
                market(),
                price("down", "Down", "0.59", "0.60"),
                new BigDecimal("1.00"),
                "maker-resolution-carry",
                "maker-resolution-carry",
                "entry"
        ), ExecutionMode.LIVE);

        assertThat(result.accepted()).isTrue();
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.ENTRY_PENDING);
        org.mockito.Mockito.verify(pythonExecutorClient).submit(any(ExecutorOrderCommand.class));
    }

    @Test
    void remoteOrderManagementMethodsPassThroughToExecutorClient() {
        ExecutorCancelOrderResponse cancel = new ExecutorCancelOrderResponse(true, "remote-1", "CANCELLED", "{}", null);
        ExecutorOrderStatusResponse status = ExecutorOrderStatusResponse.failure("remote-1", "UNKNOWN_RESPONSE", "not found");
        ExecutorOpenOrdersResponse openOrders = new ExecutorOpenOrdersResponse(true, java.util.List.of(), "{}", null);
        ExecutorFillsResponse fills = new ExecutorFillsResponse(true, java.util.List.of(), "{}", null);
        Instant since = Instant.parse("2026-05-09T12:00:00Z");

        when(pythonExecutorClient.cancelOrder("remote-1")).thenReturn(cancel);
        when(pythonExecutorClient.getOrderStatus("remote-1")).thenReturn(status);
        when(pythonExecutorClient.listOpenOrders("market-id", "token-id")).thenReturn(openOrders);
        when(pythonExecutorClient.listFills("remote-1", "market-id", "token-id", TradeSide.BUY, new BigDecimal("0.50"), new BigDecimal("5"), since)).thenReturn(fills);

        assertThat(service.cancelRemoteOrder("remote-1")).isSameAs(cancel);
        assertThat(service.fetchRemoteOrderStatus("remote-1")).isSameAs(status);
        assertThat(service.fetchOpenRemoteOrders("market-id", "token-id")).isSameAs(openOrders);
        assertThat(service.fetchRemoteFills("remote-1", "market-id", "token-id", TradeSide.BUY, new BigDecimal("0.50"), new BigDecimal("5"), since)).isSameAs(fills);

        verify(pythonExecutorClient).cancelOrder("remote-1");
        verify(pythonExecutorClient).getOrderStatus("remote-1");
        verify(pythonExecutorClient).listOpenOrders("market-id", "token-id");
        verify(pythonExecutorClient).listFills("remote-1", "market-id", "token-id", TradeSide.BUY, new BigDecimal("0.50"), new BigDecimal("5"), since);
    }

    private ExecutorOrderResponse response(BigDecimal avgPrice, BigDecimal shares, BigDecimal amountUsd, BigDecimal feeUsd) {
        return new ExecutorOrderResponse(
                true,
                true,
                "MATCHED",
                "exchange-order",
                avgPrice,
                shares,
                amountUsd,
                feeUsd,
                "matched",
                "{}",
                Instant.now()
        );
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                Instant.parse("2026-04-30T10:05:00Z"),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
    }

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(
                tokenId,
                outcome,
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                Instant.parse("2026-04-30T10:00:01Z")
        );
    }
}
