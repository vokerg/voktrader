package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.VoktraderApplication;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = VoktraderApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:transactional-order-intent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.task.scheduling.enabled=false",
                "voktrader.bots.enabled=false",
                "voktrader.trading.mode=PAPER"
        }
)
class TransactionalOrderIntentServiceTest {
    @Autowired
    private TransactionalOrderIntentService service;

    @Autowired
    private OrderDispatchOutboxRepository dispatchRepository;

    @Autowired
    private OrderIntentRepository intentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dispatchRepository.deleteAll();
        intentRepository.deleteAll();
    }

    @Test
    void cleanMigrationCreatesDurableOrderTables() {
        assertThat(tableCount("ORDER_INTENTS")).isOne();
        assertThat(tableCount("ORDER_DISPATCH_OUTBOX")).isOne();
        assertThat(columnCount("ORDER_DISPATCH_OUTBOX", "LEASE_EXPIRES_AT")).isOne();
        assertThat(columnCount("ORDER_DISPATCH_OUTBOX", "UNKNOWN_OUTCOME_DETAILS")).isOne();
    }

    @Test
    void acceptancePersistsIntentAndDispatchStateTogether() {
        AcceptedOrderIntent accepted = service.accept(
                intent(),
                ExecutionMode.PAPER,
                "risk-decision-001"
        );

        assertThat(accepted.intentId()).isNotNull();
        assertThat(accepted.dispatchId()).isNotNull();
        assertThat(accepted.clientOrderId()).startsWith("vok-");
        assertThat(accepted.intentHash()).hasSize(64);
        assertThat(accepted.intentState()).isEqualTo(OrderIntentState.ACCEPTED);
        assertThat(accepted.dispatchState()).isEqualTo(OrderDispatchState.OUTBOX_READY);
        assertThat(intentRepository.count()).isOne();
        assertThat(dispatchRepository.count()).isOne();

        OrderIntentEntity storedIntent = intentRepository
                .findByClientOrderId(accepted.clientOrderId())
                .orElseThrow();
        OrderDispatchOutboxEntity storedDispatch = dispatchRepository
                .findByClientOrderId(accepted.clientOrderId())
                .orElseThrow();

        assertThat(storedIntent.getRiskDecisionId()).isEqualTo("risk-decision-001");
        assertThat(storedIntent.getExecutionMode()).isEqualTo(ExecutionMode.PAPER);
        assertThat(storedIntent.getPayloadJson()).contains("\"tokenId\":\"token-down\"");
        assertThat(storedDispatch.getAttempts()).isZero();
        assertThat(storedDispatch.getLeaseOwner()).isNull();
        assertThat(storedDispatch.getLeaseExpiresAt()).isNull();
        assertThat(storedDispatch.getUnknownOutcomeAt()).isNull();
        assertThat(storedDispatch.getUnknownOutcomeDetails()).isNull();
    }

    @Test
    void outerRollbackCannotLeaveOnlyOneSideOfAcceptance() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.accept(intent(), ExecutionMode.LIVE, "risk-decision-rollback");
            throw new SimulatedProcessDeath();
        })).isInstanceOf(SimulatedProcessDeath.class);

        assertThat(intentRepository.count()).isZero();
        assertThat(dispatchRepository.count()).isZero();
    }

    @Test
    void restartRecoveryNeedsNoInMemoryIntent() {
        AcceptedOrderIntent accepted = service.accept(
                intent(),
                ExecutionMode.LIVE,
                "risk-decision-restart"
        );

        AcceptedOrderIntent recovered = service.recover(accepted.clientOrderId()).orElseThrow();

        assertThat(recovered).isEqualTo(accepted);
        assertThat(recovered.dispatchState()).isEqualTo(OrderDispatchState.OUTBOX_READY);
    }

    @Test
    void repeatedAcceptanceIsIdempotentForTheSameDecisionAndIntent() {
        AcceptedOrderIntent first = service.accept(
                intent(),
                ExecutionMode.LIVE,
                "risk-decision-idempotent"
        );
        AcceptedOrderIntent second = service.accept(
                intent(),
                ExecutionMode.LIVE,
                "risk-decision-idempotent"
        );

        assertThat(second).isEqualTo(first);
        assertThat(intentRepository.count()).isOne();
        assertThat(dispatchRepository.count()).isOne();
    }

    private int tableCount(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                Integer.class,
                tableName
        );
        return count == null ? 0 : count;
    }

    private int columnCount(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class,
                tableName,
                columnName
        );
        return count == null ? 0 : count;
    }

    private TradeIntent intent() {
        Instant decisionAt = Instant.parse("2026-08-05T04:00:00Z");
        return new TradeIntent(
                17L,
                "strategy-2.0",
                "entry-rule",
                "market-42",
                "market-42-slug",
                "Will the durable outbox survive restart?",
                "condition-42",
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
                "durable acceptance test",
                null
        );
    }

    private static final class SimulatedProcessDeath extends RuntimeException {
    }
}
