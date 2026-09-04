package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDispatchCancellationTest {
    @Test
    void cancellingReadyDispatchAlsoCancelsIntentAndPreventsLaterClaim() {
        Instant acceptedAt = Instant.parse("2026-08-11T03:00:00Z");
        OrderIntentEntity intent = OrderIntentEntity.accepted(
                "client-1",
                "intent-hash",
                "risk-decision",
                ExecutionMode.LIVE,
                TradeSide.BUY,
                "{}",
                acceptedAt
        );
        OrderDispatchOutboxEntity dispatch = OrderDispatchOutboxEntity.ready(intent, acceptedAt);

        dispatch.markCancelledBeforeSubmission("operator requested", acceptedAt.plusSeconds(1));
        intent.markCancelled();

        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.CANCELLED);
        assertThat(intent.getState()).isEqualTo(OrderIntentState.CANCELLED);
        assertThat(dispatch.getExecutorStatus()).isEqualTo("CANCELLED_BEFORE_SUBMIT");
        assertThatThrownBy(() -> dispatch.claim(
                "worker-1",
                acceptedAt.plusSeconds(2),
                acceptedAt.plusSeconds(12),
                2_000L
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTBOX_READY");
    }
}
