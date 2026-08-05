package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.VoktraderApplication;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.support.ExecutorTestConfig;
import com.vokerg.voktrader.support.ScriptedExecutorClient;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = VoktraderApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:order-dispatch-worker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.task.scheduling.enabled=false",
                "voktrader.bots.enabled=false",
                "voktrader.order-outbox.enabled=false",
                "voktrader.order-outbox.lease-duration=PT1S",
                "voktrader.order-outbox.batch-size=4",
                "voktrader.executor.enabled=true",
                "voktrader.trading.mode=PAPER"
        }
)
@Import(ExecutorTestConfig.class)
class OrderDispatchWorkerTest {
    @Autowired
    private TransactionalOrderIntentService acceptanceService;

    @Autowired
    private OrderDispatchStateService stateService;

    @Autowired
    private OrderDispatchWorker worker;

    @Autowired
    private OrderDispatchOutboxRepository dispatchRepository;

    @Autowired
    private OrderIntentRepository intentRepository;

    @Autowired
    private ScriptedExecutorClient executor;

    @Autowired
    private ExecutorProperties executorProperties;

    private ExecutorService concurrentWorkers;

    @BeforeEach
    void setUp() {
        dispatchRepository.deleteAll();
        intentRepository.deleteAll();
        executor.reset();
        executorProperties.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        if (concurrentWorkers != null) {
            concurrentWorkers.shutdownNow();
        }
    }

    @Test
    void crashBeforeClaimLeavesDispatchReady() {
        AcceptedOrderIntent accepted = accept("risk-before-claim");

        OrderDispatchOutboxEntity dispatch = dispatch(accepted.clientOrderId());

        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.OUTBOX_READY);
        assertThat(dispatch.getAttempts()).isZero();
        assertThat(dispatch.getLeaseOwner()).isNull();
        assertThat(dispatch.getLeaseExpiresAt()).isNull();
        assertThat(executor.submittedCommands()).isEmpty();
    }

    @Test
    void disabledExecutorLeavesReadyDispatchUnclaimed() {
        AcceptedOrderIntent accepted = accept("risk-executor-disabled");
        executorProperties.setEnabled(false);

        assertThat(worker.runOnce()).isZero();

        OrderDispatchOutboxEntity dispatch = dispatch(accepted.clientOrderId());
        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.OUTBOX_READY);
        assertThat(dispatch.getAttempts()).isZero();
        assertThat(executor.submittedCommands()).isEmpty();
    }

    @Test
    void claimCommitsSubmittingBeforeRemoteCallAndPersistsResponse() {
        AcceptedOrderIntent accepted = accept("risk-submit");
        executor.onSubmit(new ExecutorOrderResponse(
                true,
                false,
                "ACCEPTED",
                "remote-order-1",
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "accepted",
                "{\"status\":\"ACCEPTED\"}",
                Instant.parse("2026-08-05T16:00:01Z")
        ));

        assertThat(worker.runOnce()).isOne();

        OrderDispatchOutboxEntity dispatch = dispatch(accepted.clientOrderId());
        OrderIntentEntity intent = intent(accepted.clientOrderId());
        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.SUBMITTED);
        assertThat(dispatch.getAttempts()).isOne();
        assertThat(dispatch.getLeaseOwner()).isNull();
        assertThat(dispatch.getLeaseExpiresAt()).isNull();
        assertThat(dispatch.getRemoteOrderId()).isEqualTo("remote-order-1");
        assertThat(dispatch.getExecutorStatus()).isEqualTo("ACCEPTED");
        assertThat(dispatch.getExecutorResponse()).contains("ACCEPTED");
        assertThat(dispatch.getQueueLatencyMs()).isNotNegative();
        assertThat(dispatch.getSubmitRttMs()).isNotNegative();
        assertThat(intent.getState()).isEqualTo(OrderIntentState.DISPATCHED);
        assertThat(executor.submittedCommands()).hasSize(1);
        assertThat(executor.lastSubmittedCommand().idempotencyKey())
                .isEqualTo(accepted.clientOrderId());
    }

    @Test
    void expiredSubmittingLeaseMovesToReconcileWithoutBlindRetry() {
        AcceptedOrderIntent accepted = accept("risk-expired-lease");
        Instant claimedAt = Instant.parse("2026-08-05T16:00:00Z");

        OrderDispatchClaim claim = stateService.claimNext("worker-a", claimedAt).orElseThrow();
        assertThat(dispatch(accepted.clientOrderId()).getState())
                .isEqualTo(OrderDispatchState.SUBMITTING);

        assertThat(stateService.reconcileExpiredLeases(claimedAt.plusSeconds(2), 10)).isOne();

        OrderDispatchOutboxEntity reconciled = dispatch(accepted.clientOrderId());
        assertThat(reconciled.getState()).isEqualTo(OrderDispatchState.RECONCILE);
        assertThat(reconciled.getLeaseOwner()).isNull();
        assertThat(reconciled.getLeaseExpiresAt()).isNull();
        assertThat(reconciled.getUnknownOutcomeReason())
                .isEqualTo("LEASE_EXPIRED_AFTER_SUBMITTING");
        assertThat(stateService.claimNext("worker-b", claimedAt.plusSeconds(3))).isEmpty();
        assertThat(executor.submittedCommands()).isEmpty();
        assertThat(claim.clientOrderId()).isEqualTo(accepted.clientOrderId());
    }

    @Test
    void concurrentWorkersCannotClaimTheSameClientOrderId() throws Exception {
        accept("risk-concurrent-claim");
        concurrentWorkers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Instant now = Instant.parse("2026-08-05T16:00:00Z");

        Future<Optional<OrderDispatchClaim>> first = concurrentWorkers.submit(() -> {
            start.await();
            return stateService.claimNext("worker-a", now);
        });
        Future<Optional<OrderDispatchClaim>> second = concurrentWorkers.submit(() -> {
            start.await();
            return stateService.claimNext("worker-b", now);
        });
        start.countDown();

        List<Optional<OrderDispatchClaim>> claims = List.of(first.get(), second.get());
        assertThat(claims.stream().filter(Optional::isPresent).count()).isOne();
        assertThat(dispatchRepository.findAll()).singleElement().satisfies(dispatch -> {
            assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.SUBMITTING);
            assertThat(dispatch.getAttempts()).isOne();
            assertThat(dispatch.getLeaseOwner()).isIn("worker-a", "worker-b");
        });
    }

    @Test
    void deterministicExecutorRejectionCompletesWithoutRetry() {
        AcceptedOrderIntent accepted = accept("risk-rejected");
        executor.onSubmit(ExecutorOrderResponse.rejected("venue rejected order"));

        assertThat(worker.runOnce()).isOne();

        OrderDispatchOutboxEntity dispatch = dispatch(accepted.clientOrderId());
        assertThat(dispatch.getState()).isEqualTo(OrderDispatchState.FAILED);
        assertThat(dispatch.getLastError()).contains("venue rejected order");
        assertThat(dispatch.getLeaseOwner()).isNull();
        assertThat(intent(accepted.clientOrderId()).getState())
                .isEqualTo(OrderIntentState.REJECTED);
        assertThat(stateService.claimNext("worker-b", Instant.now())).isEmpty();
    }

    private AcceptedOrderIntent accept(String riskDecisionId) {
        return acceptanceService.accept(
                intent(),
                ExecutionMode.LIVE,
                riskDecisionId
        );
    }

    private OrderDispatchOutboxEntity dispatch(String clientOrderId) {
        return dispatchRepository.findByClientOrderId(clientOrderId).orElseThrow();
    }

    private OrderIntentEntity intent(String clientOrderId) {
        return intentRepository.findByClientOrderId(clientOrderId).orElseThrow();
    }

    private TradeIntent intent() {
        Instant decisionAt = Instant.parse("2026-08-05T15:59:59Z");
        return new TradeIntent(
                21L,
                "strategy-2.0",
                "entry-rule",
                "market-21",
                "market-21-slug",
                "Will the outbox worker preserve one submission?",
                "condition-21",
                "token-down",
                "Down",
                TradeSide.BUY,
                new BigDecimal("2.50"),
                new BigDecimal("5"),
                TradeOrderType.FOK,
                false,
                new BigDecimal("0.50"),
                new BigDecimal("0.49"),
                new BigDecimal("0.50"),
                new BigDecimal("0.01"),
                new BigDecimal("0.495"),
                decisionAt.minusSeconds(1),
                1000L,
                decisionAt,
                decisionAt.plusSeconds(3600),
                3600L,
                "durable worker test",
                null
        );
    }
}
