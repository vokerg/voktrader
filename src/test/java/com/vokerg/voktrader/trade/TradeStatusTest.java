package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import com.vokerg.voktrader.trade.model.TradeStatus;

import static org.assertj.core.api.Assertions.assertThat;

class TradeStatusTest {
    @Test
    void activeStatusesCoverOpenTradeLifecycle() {
        assertThat(TradeStatus.ENTRY_PENDING.isActive()).isTrue();
        assertThat(TradeStatus.PARTIALLY_OPEN.isActive()).isTrue();
        assertThat(TradeStatus.OPEN.isActive()).isTrue();
        assertThat(TradeStatus.EXIT_PENDING.isActive()).isTrue();
        assertThat(TradeStatus.PARTIALLY_CLOSED.isActive()).isTrue();
    }

    @Test
    void terminalStatusesCoverFinishedTradeLifecycleAndExistingRejections() {
        assertThat(TradeStatus.CLOSED.isTerminal()).isTrue();
        assertThat(TradeStatus.RESOLVED.isTerminal()).isTrue();
        assertThat(TradeStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TradeStatus.FAILED.isTerminal()).isTrue();
        assertThat(TradeStatus.RISK_REJECTED.isTerminal()).isTrue();
        assertThat(TradeStatus.ENTRY_REJECTED.isTerminal()).isTrue();
    }

    @Test
    void positionExposureOnlyAppliesAfterEntryFillBegins() {
        assertThat(TradeStatus.PARTIALLY_OPEN.hasPosition()).isTrue();
        assertThat(TradeStatus.OPEN.hasPosition()).isTrue();
        assertThat(TradeStatus.EXIT_PENDING.hasPosition()).isTrue();
        assertThat(TradeStatus.PARTIALLY_CLOSED.hasPosition()).isTrue();
        assertThat(TradeStatus.NEW.hasPosition()).isFalse();
        assertThat(TradeStatus.ENTRY_PENDING.hasPosition()).isFalse();
        assertThat(TradeStatus.CLOSED.hasPosition()).isFalse();
    }

    @Test
    void pendingEntryAndExitAreExplicit() {
        assertThat(TradeStatus.ENTRY_PENDING.isPendingEntry()).isTrue();
        assertThat(TradeStatus.EXIT_PENDING.isPendingEntry()).isFalse();
        assertThat(TradeStatus.EXIT_PENDING.isPendingExit()).isTrue();
        assertThat(TradeStatus.ENTRY_PENDING.isPendingExit()).isFalse();
    }
}
