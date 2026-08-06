package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.VoktraderApplication;
import com.vokerg.voktrader.api.runtime.OrderDispatchUncertaintyController;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorSubmissionException;
import com.vokerg.voktrader.executor.ExecutorSubmissionFailureType;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.support.ExecutorTestConfig;
import com.vokerg.voktrader.support.ScriptedExecutorClient;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = VoktraderApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:unknown-submission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.task.scheduling.enabled=false",
                "voktrader.bots.enabled=false",
                "voktrader.order-outbox.enabled=false",
                "voktrader.order-outbox.lease-duration=PT1S",
                "voktrader.executor.enabled=true",
                "voktrader.trading.mode=PAPER"
        }
)
@Import(ExecutorTestConfig.class)
class UnknownSubmissionOutcomeTest {
    @Autowired private TransactionalOrderIntentService acceptanceService;
    @Autowired private OrderDispatchWorker worker;
    @Autowired private OrderDispatchStateService stateService;
    @Autowired private OrderDispatchOutboxRepository dispatchRepository;
    @Autowired private OrderIntentRepository intentRepository;
    @Autowired private ScriptedExecutorClient executor;
    @Autowired private ExecutorProperties executorProperties;
    @Autowired private OrderDispatchUncertaintyController controller;

    @BeforeEach
    void setUp() {
        dispatchRepository.deleteAll();
        intentRepository.deleteAll();
        executor.reset();
        executorProperties.setEnabled(true);
    }

    @Test
    void everyRequiredAmbiguityClassPersistsUnknownAndNeverBlindRetries() {
        List<ExecutorSubmissionFailureType> types = List.of(
                ExecutorSubmissionFailureType.TIMEOUT,
                ExecutorSubmissionFailureType.CONNECTION_RESET,
                ExecutorSubmissionFailureType.HTTP_425_TOO_EARLY,
                ExecutorSubmissionFailureType.HTTP_503_SERVICE_UNAVAILABLE,
                ExecutorSubmissionFailureType.MALFORMED_RESPONSE,
                ExecutorSubmissionFailureType.EXECUTOR_CRASH
        );

        for (int index = 0; index < types.size(); index++) {
            ExecutorSubmissionFailureType type = types.get(index);
            AcceptedOrderIntent accepted = acceptanceService.accept(
                    intent(index), ExecutionMode.LIVE, "risk-unknown-" + index
            );
            executor.onSubmitFailure(ExecutorSubmissionException.transport(type, "ambiguous " + type, null));

            assertThat(worker.runOnce()).isOne();

            OrderDispatchOutboxEntity dispatch = dispatchRepository
                    .findByClientOrderId(accepted.clientOrderId()).orElseThrow();
            assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.UNKNOWN);
            assertThat(dispatch.getUnknownOutcomeReason()).isEqualTo(type.name());
            assertThat(dispatch.getUnknownOutcomeAt()).isNotNull();
            assertThat(dispatch.getLeaseOwner()).isNull();
            assertThat(stateService.claimNext("retry-worker", Instant.now().plusSeconds(10))).isEmpty();
            assertThat(executor.submittedCommands()).hasSize(index + 1);

            stateService.resolveRejected(
                    accepted.clientOrderId(), "remote reconciliation found no matching order", Instant.now()
            );
        }
    }

    @Test
    void nonTerminalHttp200ResponsePersistsUnknownInsteadOfFalseRejection() {
        AcceptedOrderIntent accepted = acceptanceService.accept(
                intent(10), ExecutionMode.LIVE, "risk-http-200-failed"
        );
        executor.onSubmit(new ExecutorOrderResponse(
                false,
                false,
                "FAILED",
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "exchange client failed after submission began",
                "{\"status\":\"FAILED\"}",
                Instant.now()
        ));

        assertThat(worker.runOnce()).isOne();

        OrderDispatchOutboxEntity dispatch = dispatchRepository
                .findByClientOrderId(accepted.clientOrderId()).orElseThrow();
        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.UNKNOWN);
        assertThat(dispatch.getUnknownOutcomeReason()).isEqualTo("EXECUTOR_CRASH");
        assertThat(dispatch.getUnknownOutcomeDetails()).contains("non-terminal submission response");
        assertThat(intentRepository.findByClientOrderId(accepted.clientOrderId()).orElseThrow().getState())
                .isEqualTo(OrderIntentState.ACCEPTED);
        assertThat(stateService.claimNext("retry-worker", Instant.now().plusSeconds(10))).isEmpty();
    }

    @Test
    void unknownBlocksConflictingAcceptanceUntilEvidenceBackedResolution() {
        AcceptedOrderIntent accepted = acceptanceService.accept(intent(20), ExecutionMode.LIVE, "risk-blocking");
        executor.onSubmitFailure(ExecutorSubmissionException.transport(
                ExecutorSubmissionFailureType.TIMEOUT, "timed out after bytes were sent", null
        ));
        worker.runOnce();

        assertThatThrownBy(() -> acceptanceService.accept(
                intent(21), ExecutionMode.LIVE, "risk-conflicting"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome is unresolved");
        assertThat(controller.unresolved()).singleElement().satisfies(status -> {
            assertThat(status.clientOrderId()).isEqualTo(accepted.clientOrderId());
            assertThat(status.state()).isEqualTo("UNKNOWN");
            assertThat(status.reason()).isEqualTo("TIMEOUT");
            assertThat(status.details()).contains("bytes were sent");
        });

        stateService.resolveAccepted(
                accepted.clientOrderId(), "remote-order-reconciled", "unique remote match", Instant.now()
        );

        assertThat(acceptanceService.accept(
                intent(21), ExecutionMode.LIVE, "risk-conflicting"
        ).dispatchState()).isEqualTo(OrderDispatchState.OUTBOX_READY);
    }

    @Test
    void nonUniqueRemoteTruthEscalatesToManualReviewAndRemainsExposureBlocking() {
        AcceptedOrderIntent accepted = acceptanceService.accept(intent(30), ExecutionMode.LIVE, "risk-manual");
        executor.onSubmitFailure(ExecutorSubmissionException.transport(
                ExecutorSubmissionFailureType.CONNECTION_RESET, "connection reset", null
        ));
        worker.runOnce();

        stateService.markManualReview(
                accepted.clientOrderId(), "two remote orders match the durable intent", Instant.now()
        );

        OrderDispatchOutboxEntity dispatch = dispatchRepository
                .findByClientOrderId(accepted.clientOrderId()).orElseThrow();
        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.MANUAL_REVIEW);
        assertThat(dispatch.getUnknownOutcomeReason()).isEqualTo("REMOTE_TRUTH_NOT_UNIQUE");
        assertThatThrownBy(() -> acceptanceService.accept(
                intent(31), ExecutionMode.LIVE, "risk-after-manual"
        )).isInstanceOf(IllegalStateException.class);
    }

    private TradeIntent intent(int offset) {
        Instant decisionAt = Instant.parse("2026-08-05T20:00:00Z").plusSeconds(offset);
        return new TradeIntent(
                2300L + offset, "strategy-2.0", "entry-rule", "market-23-" + offset,
                "market-23-slug-" + offset, "Will ambiguity remain quarantined?", "condition-23",
                "token-23-" + offset, "Yes", TradeSide.BUY, new BigDecimal("2.50"),
                new BigDecimal("5"), TradeOrderType.FOK, false, new BigDecimal("0.50"),
                new BigDecimal("0.49"), new BigDecimal("0.50"), new BigDecimal("0.01"),
                new BigDecimal("0.495"), decisionAt.minusSeconds(1), 1000L, decisionAt,
                decisionAt.plusSeconds(3600), 3600L, "unknown outcome test", null
        );
    }
}
