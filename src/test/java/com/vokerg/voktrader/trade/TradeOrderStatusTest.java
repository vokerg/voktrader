package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeOrderStatusTest {
    @Test
    void activeStatusesCoverCurrentAndFutureOrderLifecycle() {
        assertThat(TradeOrderStatus.CREATED.isActive()).isTrue();
        assertThat(TradeOrderStatus.SUBMITTING.isActive()).isTrue();
        assertThat(TradeOrderStatus.SUBMITTED.isActive()).isTrue();
        assertThat(TradeOrderStatus.RESTING.isActive()).isTrue();
        assertThat(TradeOrderStatus.OPEN.isActive()).isTrue();
        assertThat(TradeOrderStatus.PARTIALLY_FILLED.isActive()).isTrue();
        assertThat(TradeOrderStatus.PARTIAL.isActive()).isTrue();
        assertThat(TradeOrderStatus.CANCEL_REQUESTED.isActive()).isTrue();
    }

    @Test
    void terminalStatusesCoverCurrentAndFutureOrderLifecycle() {
        assertThat(TradeOrderStatus.FILLED.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.TIMEOUT.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.REJECTED.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.RISK_REJECTED.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.FAILED.isTerminal()).isTrue();
        assertThat(TradeOrderStatus.SHADOW_RECORDED.isTerminal()).isTrue();
    }

    @Test
    void filledOrPartiallyFilledRecognizesFullAndPartialFillStates() {
        assertThat(TradeOrderStatus.FILLED.isFilledOrPartiallyFilled()).isTrue();
        assertThat(TradeOrderStatus.PARTIALLY_FILLED.isFilledOrPartiallyFilled()).isTrue();
        assertThat(TradeOrderStatus.PARTIAL.isFilledOrPartiallyFilled()).isTrue();
        assertThat(TradeOrderStatus.RESTING.isFilledOrPartiallyFilled()).isFalse();
        assertThat(TradeOrderStatus.REJECTED.isFilledOrPartiallyFilled()).isFalse();
    }
}
