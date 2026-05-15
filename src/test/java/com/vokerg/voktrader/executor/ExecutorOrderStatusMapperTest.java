package com.vokerg.voktrader.executor;

import org.junit.jupiter.api.Test;

import com.vokerg.voktrader.trade.model.TradeOrderStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorOrderStatusMapperTest {
    @Test
    void mapsKnownExecutorStatusesToLifecycleStatuses() {
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus("MATCHED")).isEqualTo(TradeOrderStatus.FILLED);
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus("LIVE")).isEqualTo(TradeOrderStatus.SUBMITTED);
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus("PARTIALLY_MATCHED")).isEqualTo(TradeOrderStatus.PARTIALLY_FILLED);
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus("CANCELED")).isEqualTo(TradeOrderStatus.CANCELLED);
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus("TIMED_OUT")).isEqualTo(TradeOrderStatus.EXPIRED);
    }

    @Test
    void unknownStatusesMapToUnknown() {
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus(null)).isEqualTo(TradeOrderStatus.UNKNOWN);
        assertThat(ExecutorOrderStatusMapper.toLifecycleStatus("mystery-state")).isEqualTo(TradeOrderStatus.UNKNOWN);
    }

    @Test
    void responseExposesMappedLifecycleStatus() {
        ExecutorOrderStatusResponse response = new ExecutorOrderStatusResponse(
                true,
                "order-1",
                "MATCHED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "{}",
                null
        );

        assertThat(response.lifecycleStatus()).isEqualTo(TradeOrderStatus.FILLED);
    }
}
