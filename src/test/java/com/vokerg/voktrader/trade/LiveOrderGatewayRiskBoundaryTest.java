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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveOrderGatewayRiskBoundaryTest {
    private final OrderManager orderManager = mock(OrderManager.class);
    private final LiveOrderGateway gateway = new LiveOrderGateway(orderManager);

    @Test
    void rawBuyWithoutCentralApprovalIsRejectedBeforeOrderManager() {
        EntryIntent entry = entryIntent();

        OrderLifecycleResult result = gateway.submitOrder(
                entry.tradeIntent(), entry.owner(), ExecutionMode.LIVE);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("central entry risk decision");
        verify(orderManager, never()).submitOrder(any(), any());
    }

    @Test
    void matchingApprovedDecisionAllowsExactlyThatBuy() {
        EntryIntent entry = entryIntent();
        EntryRiskRequest request = EntryRiskRequest.of(entry, ExecutionMode.LIVE);
        RiskAssessment assessment = new RiskAssessment(request.correlationId());
        when(orderManager.submitOrder(entry.tradeIntent(), ExecutionMode.LIVE)).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "local", "remote", TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.SUBMITTED, "submitted", null));

        OrderLifecycleResult result = EntryRiskDecisionContext.withApproved(
                request,
                assessment,
                () -> gateway.submitOrder(entry.tradeIntent(), entry.owner(), ExecutionMode.LIVE)
        );

        assertThat(result.success()).isTrue();
        verify(orderManager).submitOrder(entry.tradeIntent(), ExecutionMode.LIVE);
    }

    @Test
    void sellRemainsAvailableWithoutEntryApproval() {
        ExitIntent exit = ExitIntent.sell(
                market(), price(), new BigDecimal("2"), "strategy", "exit", "reduce exposure");
        when(orderManager.submitOrder(exit.tradeIntent(), ExecutionMode.LIVE)).thenReturn(new OrderLifecycleResult(
                true, 1L, 2L, "local", "remote", TradeStatus.EXIT_PENDING,
                TradeOrderStatus.SUBMITTED, "submitted", null));

        OrderLifecycleResult result = gateway.submitOrder(
                exit.tradeIntent(), exit.owner(), ExecutionMode.LIVE);

        assertThat(result.success()).isTrue();
        verify(orderManager).submitOrder(exit.tradeIntent(), ExecutionMode.LIVE);
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
