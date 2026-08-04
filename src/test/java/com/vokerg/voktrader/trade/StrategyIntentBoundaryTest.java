package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.RiskSeverity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeRiskCheckEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyIntentBoundaryTest {
    private final StrategyV2ExecutionProperties executionProperties = new StrategyV2ExecutionProperties();
    private final ExecutionRouter executionRouter = mock(ExecutionRouter.class);
    private final OrderGateway orderGateway = mock(OrderGateway.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
    private final TradeRiskCheckRepository riskCheckRepository = mock(TradeRiskCheckRepository.class);
    private final StrategyIntentBoundary boundary = new StrategyIntentBoundary(
            executionProperties,
            executionRouter,
            orderGateway,
            tradingProperties,
            riskCheckService,
            riskCheckRepository
    );

    @BeforeEach
    void setUp() {
        tradingProperties.setMode(ExecutionMode.PAPER);
        when(riskCheckService.assessEntry(any())).thenAnswer(invocation -> {
            EntryRiskRequest request = invocation.getArgument(0);
            RiskAssessment assessment = new RiskAssessment(request.correlationId());
            assessment.add(TradeRiskCheckEntity.of(
                    null, null, request.correlationId(), request.mode(), "TEST_POLICY", true,
                    RiskSeverity.INFO, "ok", "ok", "accepted"));
            return assessment;
        });
    }

    @Test
    void compatibilityModeAssessesPersistsThenRoutesTypedEntryExactlyOnce() {
        when(executionRouter.route(any())).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER, 1L, 2L, TradeStatus.OPEN, TradeOrderStatus.FILLED, "accepted"));

        TradeExecutionResult result = boundary.accept(entryIntent());

        assertThat(result.accepted()).isTrue();
        ArgumentCaptor<EntryRiskRequest> request = ArgumentCaptor.forClass(EntryRiskRequest.class);
        verify(riskCheckService).assessEntry(request.capture());
        assertThat(request.getValue().correlationId()).startsWith("ENTRY:PAPER:");
        assertThat(request.getValue().tradeIntent().side()).isEqualTo(TradeSide.BUY);

        InOrder order = inOrder(riskCheckService, riskCheckRepository, executionRouter);
        order.verify(riskCheckService).assessEntry(any());
        order.verify(riskCheckRepository).saveAll(any());
        order.verify(executionRouter).route(any());
        verify(orderGateway, never()).submitOrder(any(), any(), any());
    }

    @Test
    void orderLayerModeUsesTheSameSingleCentralAssessment() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(), any(), any())).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "local-1", "remote-1", TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.SUBMITTED, "submitted", null));

        TradeExecutionResult result = boundary.accept(entryIntent());

        assertThat(result.accepted()).isTrue();
        assertThat(result.localOrderId()).isEqualTo("local-1");
        verify(riskCheckService).assessEntry(any());
        verify(riskCheckRepository).saveAll(any());
        verify(orderGateway).submitOrder(any(), any(), any());
        verify(executionRouter, never()).route(any());
    }

    @Test
    void blockingAssessmentIsPersistedAndCannotReachEitherExecutionPath() {
        RiskAssessment blocked = new RiskAssessment("ENTRY:PAPER:blocked");
        blocked.add(TradeRiskCheckEntity.of(
                null, null, blocked.correlationId(), ExecutionMode.PAPER, "KILL_SWITCH", false,
                RiskSeverity.BLOCK, true, false, "kill switch is enabled"));
        doReturn(blocked).when(riskCheckService).assessEntry(any());

        TradeExecutionResult result = boundary.accept(entryIntent());

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).isEqualTo("kill switch is enabled");
        verify(riskCheckRepository).saveAll(blocked.checks());
        verify(executionRouter, never()).route(any());
        verify(orderGateway, never()).submitOrder(any(), any(), any());
    }

    @Test
    void exitDoesNotCrossEntryRiskPolicy() {
        executionProperties.setUseOrderLayer(true);
        when(orderGateway.submitOrder(any(), any(), any())).thenReturn(new OrderLifecycleResult(
                true, 3L, 4L, "local-exit", "remote-exit", TradeStatus.EXIT_PENDING,
                TradeOrderStatus.SUBMITTED, "submitted", null));

        boundary.submit(exitIntent());

        ArgumentCaptor<TradeIntent> routed = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderGateway).submitOrder(routed.capture(), any(), any());
        assertThat(routed.getValue().side()).isEqualTo(TradeSide.SELL);
        verify(riskCheckService, never()).assessEntry(any());
        verify(riskCheckRepository, never()).saveAll(any());
    }

    private EntryIntent entryIntent() {
        return EntryIntent.buy(market(), price(), new BigDecimal("1.00"),
                "strategy-v2-test", "entry", "test entry");
    }

    private ExitIntent exitIntent() {
        return ExitIntent.sell(market(), price(), new BigDecimal("2.00"),
                "strategy-v2-test", "exit", "test exit");
    }

    private OutcomePrice price() {
        return new OutcomePrice("token-id", "Up", new BigDecimal("0.49"),
                new BigDecimal("0.51"), new BigDecimal("0.02"), Instant.now());
    }

    private GammaMarketDto market() {
        return new GammaMarketDto("market-id", "Question", "condition-id", "slug",
                Instant.now().plusSeconds(900), true, false, true, false,
                null, null, null, null);
    }
}
