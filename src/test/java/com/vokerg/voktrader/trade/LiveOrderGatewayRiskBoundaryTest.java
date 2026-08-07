package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveOrderGatewayRiskBoundaryTest {
    private final DurableOrderAcceptanceService acceptanceService = mock(DurableOrderAcceptanceService.class);
    private final DurableOrderCancellationService cancellationService = mock(DurableOrderCancellationService.class);
    private final LiveOrderGateway gateway = new LiveOrderGateway(acceptanceService, cancellationService);

    @Test
    void rawBuyWithoutCentralApprovalIsRejectedBeforeDurableAcceptance() {
        EntryIntent entry = entryIntent();

        OrderLifecycleResult result = gateway.submitOrder(
                entry.tradeIntent(), entry.owner(), ExecutionMode.LIVE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("central entry risk decision");
        verify(acceptanceService, never()).accept(any(), any(), anyString());
    }

    @Test
    void matchingApprovedDecisionRoutesExactlyThatBuyToDurableAcceptance() {
        EntryIntent entry = entryIntent();
        EntryRiskRequest request = EntryRiskRequest.of(entry, ExecutionMode.LIVE);
        RiskAssessment assessment = new RiskAssessment(request.correlationId());
        when(acceptanceService.accept(entry.tradeIntent(), ExecutionMode.LIVE, request.correlationId()))
                .thenReturn(new OrderLifecycleResult(
                        true, 1L, 2L, "local", null, TradeStatus.ENTRY_PENDING,
                        TradeOrderStatus.CREATED, "accepted for durable dispatch", null));

        OrderLifecycleResult result = EntryRiskDecisionContext.withApproved(
                request,
                assessment,
                () -> gateway.submitOrder(entry.tradeIntent(), entry.owner(), ExecutionMode.LIVE)
        );

        assertThat(result.success()).isTrue();
        verify(acceptanceService).accept(entry.tradeIntent(), ExecutionMode.LIVE, request.correlationId());
    }

    @Test
    void sellRemainsAvailableWithoutEntryApproval() {
        ExitIntent exit = ExitIntent.sell(
                market(), price(), new BigDecimal("2"), "strategy", "exit", "reduce exposure");
        when(acceptanceService.accept(any(), any(), anyString())).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "local", null, TradeStatus.EXIT_PENDING,
                TradeOrderStatus.CREATED, "accepted for durable dispatch", null));

        OrderLifecycleResult result = gateway.submitOrder(
                exit.tradeIntent(), exit.owner(), ExecutionMode.LIVE);

        assertThat(result.success()).isTrue();
        verify(acceptanceService).accept(any(), any(), anyString());
    }

    private EntryIntent entryIntent() {
        return EntryIntent.buy(
                market(), price(), new BigDecimal("1"), "strategy", "entry", "open exposure");
    }

    private OutcomePrice price() {
        return new OutcomePrice(
                "token", "Up", new BigDecimal("0.49"), new BigDecimal("0.51"),
                new BigDecimal("0.02"), Instant.now());
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market", "Question", "condition", "slug", Instant.now().plusSeconds(900),
                true, false, true, false, null, null, null, null);
    }
}
