package com.vokerg.voktrader.strategy.v2;

import com.vokerg.voktrader.marketdata.TickMath;
import com.vokerg.voktrader.marketdata.TickRounding;
import com.vokerg.voktrader.marketdata.TickSizeService;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.strategy.StrategyOutcomeView;
import com.vokerg.voktrader.trade.EntryAcceptanceService;
import com.vokerg.voktrader.trade.EntryIntent;
import com.vokerg.voktrader.trade.ExitSubmissionService;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyV2OrderActionBuilderTest {
    private final EntryAcceptanceService entryAcceptanceService = mock(EntryAcceptanceService.class);
    private final ExitSubmissionService exitSubmissionService = mock(ExitSubmissionService.class);
    private final TradingProperties tradingProperties = new TradingProperties();
    private final TickSizeService tickSizeService = mock(TickSizeService.class);
    private final StrategyV2OrderActionBuilder builder = new StrategyV2OrderActionBuilder(
            entryAcceptanceService,
            exitSubmissionService,
            tradingProperties,
            tickSizeService
    );

    @BeforeEach
    void setUp() {
        tradingProperties.setMode(ExecutionMode.PAPER);
        when(tickSizeService.requireTickSize(anyString())).thenReturn(new BigDecimal("0.01"));
        when(tickSizeService.round(anyString(), any(BigDecimal.class), any(TickRounding.class)))
                .thenAnswer(invocation -> TickMath.round(
                        invocation.getArgument(1),
                        new BigDecimal("0.01"),
                        invocation.getArgument(2)
                ));
        when(entryAcceptanceService.accept(any(EntryIntent.class))).thenReturn(TradeExecutionResult.accepted(
                ExecutionMode.PAPER,
                1L,
                2L,
                TradeStatus.OPEN,
                TradeOrderStatus.FILLED,
                "accepted"
        ));
    }

    @Test
    void routeEntryEmitsTypedIntentOnly() {
        TradeExecutionResult result = builder.routeEntry(strategy(TradeOrderType.FOK), context());

        assertThat(result.accepted()).isTrue();
        ArgumentCaptor<EntryIntent> intent = ArgumentCaptor.forClass(EntryIntent.class);
        verify(entryAcceptanceService).accept(intent.capture());
        verify(exitSubmissionService, never()).submit(any());
        assertThat(intent.getValue().side().name()).isEqualTo("BUY");
        assertThat(intent.getValue().strategyId()).isEqualTo("strategy-v2-test");
        assertThat(intent.getValue().orderType()).isEqualTo(TradeOrderType.FOK);
    }

    @Test
    void fixedSharesSizeSetsRequestedSharesAndNotionalFromLimitPrice() {
        builder.routeEntry(fixedSharesStrategy(), context());

        ArgumentCaptor<EntryIntent> intent = ArgumentCaptor.forClass(EntryIntent.class);
        verify(entryAcceptanceService).accept(intent.capture());
        assertThat(intent.getValue().shares()).isEqualByComparingTo("5.00");
        assertThat(intent.getValue().amountUsd()).isEqualByComparingTo("2.55");
    }

    @Test
    void makerFixedSharesUsesConfiguredMinimumWhenSharesOmitted() {
        tradingProperties.setMinMakerOrderShares(new BigDecimal("6.00"));
        StrategyV2Properties.Strategy strategy = fixedSharesStrategy();
        strategy.getEntry().getAction().getSize().setShares(null);

        builder.routeEntry(strategy, context());

        ArgumentCaptor<EntryIntent> intent = ArgumentCaptor.forClass(EntryIntent.class);
        verify(entryAcceptanceService).accept(intent.capture());
        assertThat(intent.getValue().shares()).isEqualByComparingTo("6.00");
        assertThat(intent.getValue().amountUsd()).isEqualByComparingTo("3.06");
    }

    @Test
    void gtdEntryCarriesTypedRestingTtl() {
        StrategyV2Properties.Strategy strategy = fixedSharesStrategy();
        StrategyV2Properties.MakerLifecycle lifecycle = new StrategyV2Properties.MakerLifecycle();
        lifecycle.setCancelAfterSeconds(12);
        strategy.getEntry().getAction().setMakerLifecycle(lifecycle);

        builder.routeEntry(strategy, context());

        ArgumentCaptor<EntryIntent> intent = ArgumentCaptor.forClass(EntryIntent.class);
        verify(entryAcceptanceService).accept(intent.capture());
        assertThat(intent.getValue().restingTtlSeconds()).isEqualTo(12);
    }

    @Test
    void fixedSharesRejectsWhenNotionalExceedsMaxUsdBeforeBoundary() {
        StrategyV2Properties.Strategy strategy = fixedSharesStrategy();
        strategy.getEntry().getAction().getSize().setMaxUsd(new BigDecimal("2.50"));

        TradeExecutionResult result = builder.routeEntry(strategy, context());

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).contains("fixed_shares");
        verify(entryAcceptanceService, never()).accept(any());
    }

    @Test
    void staleConfiguredTickIsRejectedBeforeEntryBoundary() {
        StrategyV2Properties.Strategy strategy = strategy(TradeOrderType.FOK);
        strategy.getEntry().getAction().getPrice().setTickSize(new BigDecimal("0.001"));

        TradeExecutionResult result = builder.routeEntry(strategy, context());

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).contains("configured tick", "current tick");
        verify(entryAcceptanceService, never()).accept(any());
    }

    private StrategyV2Properties.Strategy strategy(TradeOrderType orderType) {
        StrategyV2Properties.Strategy strategy = new StrategyV2Properties.Strategy();
        strategy.setStrategyId("strategy-v2-test");
        StrategyV2Properties.Entry entry = new StrategyV2Properties.Entry();
        entry.setRuleId("entry");
        StrategyV2Properties.Action action = new StrategyV2Properties.Action();
        action.setOrderType(orderType.name());
        action.setPostOnly(orderType.canRestOnBook());
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setUsd(new BigDecimal("1.00"));
        action.setSize(size);
        entry.setAction(action);
        strategy.setEntry(entry);
        return strategy;
    }

    private StrategyV2Properties.Strategy fixedSharesStrategy() {
        StrategyV2Properties.Strategy strategy = strategy(TradeOrderType.GTD);
        StrategyV2Properties.Action action = strategy.getEntry().getAction();
        StrategyV2Properties.Size size = new StrategyV2Properties.Size();
        size.setType("fixed_shares");
        size.setShares(new BigDecimal("5.00"));
        action.setSize(size);
        return strategy;
    }

    private StrategyV2FeatureContext context() {
        StrategyOutcomeView candidate = mock(StrategyOutcomeView.class);
        when(candidate.tokenId()).thenReturn("token-id");
        when(candidate.outcome()).thenReturn("Up");
        when(candidate.spread()).thenReturn(new BigDecimal("0.02"));
        Map<String, Object> features = new HashMap<>();
        features.put("candidate.bid", new BigDecimal("0.49"));
        features.put("candidate.ask", new BigDecimal("0.51"));
        return new StrategyV2FeatureContext(market(), null, candidate, null, Instant.parse("2026-05-09T12:00:00Z"), features);
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "Question",
                "condition-id",
                "slug",
                Instant.parse("2026-05-09T12:05:00Z"),
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
}
