package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.paper.PaperExecutionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ExecutionRouterRiskBoundaryTest {
    private final TradingProperties properties = new TradingProperties();
    private final PaperExecutionService paperExecutionService = mock(PaperExecutionService.class);
    private final LiveExecutionService liveExecutionService = mock(LiveExecutionService.class);
    private final ExecutionRouter router = new ExecutionRouter(
            properties, paperExecutionService, liveExecutionService);

    @Test
    void rawBacktestBuyCannotUseExecutionOverrideToBypassRisk() {
        properties.setMode(ExecutionMode.BACKTEST);
        AtomicReference<TradeExecutionResult> result = new AtomicReference<>();

        ExecutionOverrideContext.runWith(
                intent -> TradeExecutionResult.accepted(
                        ExecutionMode.BACKTEST, 1L, 2L, TradeStatus.OPEN,
                        TradeOrderStatus.FILLED, "override accepted"),
                () -> result.set(router.route(entryIntent().tradeIntent()))
        );

        assertThat(result.get().accepted()).isFalse();
        assertThat(result.get().message()).contains("EntryAcceptanceService");
    }

    @Test
    void centrallyApprovedBacktestBuyMayUseExecutionOverride() {
        properties.setMode(ExecutionMode.BACKTEST);
        EntryIntent entry = entryIntent();
        EntryRiskRequest request = EntryRiskRequest.of(entry, ExecutionMode.BACKTEST);
        RiskAssessment assessment = new RiskAssessment(request.correlationId());
        AtomicReference<TradeExecutionResult> result = new AtomicReference<>();

        EntryRiskDecisionContext.withApproved(request, assessment, () -> {
            ExecutionOverrideContext.runWith(
                    intent -> TradeExecutionResult.accepted(
                            ExecutionMode.BACKTEST, 1L, 2L, TradeStatus.OPEN,
                            TradeOrderStatus.FILLED, "override accepted"),
                    () -> result.set(router.route(entry.tradeIntent()))
            );
            return null;
        });

        assertThat(result.get().accepted()).isTrue();
        verify(paperExecutionService, never()).execute(entry.tradeIntent());
        verify(liveExecutionService, never()).execute(entry.tradeIntent(), ExecutionMode.BACKTEST);
    }

    private EntryIntent entryIntent() {
        GammaMarketDto market = new GammaMarketDto(
                "market", "Question", "condition", "slug", Instant.now().plusSeconds(900),
                true, false, true, false, null, null, null, null);
        OutcomePrice price = new OutcomePrice(
                "token", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"),
                new BigDecimal("0.02"), Instant.now());
        return EntryIntent.buy(market, price, BigDecimal.ONE, "strategy", "entry", "test");
    }
}
