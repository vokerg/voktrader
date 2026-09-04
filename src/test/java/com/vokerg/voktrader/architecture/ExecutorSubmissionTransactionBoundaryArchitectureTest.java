package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorSubmissionTransactionBoundaryArchitectureTest {
    private static final Path EXECUTION_ROUTER = Path.of(
            "src/main/java/com/vokerg/voktrader/trade/ExecutionRouter.java"
    );
    private static final Path LIVE_GATEWAY = Path.of(
            "src/main/java/com/vokerg/voktrader/trade/LiveOrderGateway.java"
    );
    private static final List<Path> TRANSACTIONAL_ACCEPTANCE_SOURCES = List.of(
            Path.of("src/main/java/com/vokerg/voktrader/trade/DurableOrderAcceptanceService.java"),
            Path.of("src/main/java/com/vokerg/voktrader/trade/outbox/TransactionalOrderIntentService.java")
    );
    private static final Path WORKER = Path.of(
            "src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchWorker.java"
    );
    private static final Path NETWORK_BOUNDARY = Path.of(
            "src/main/java/com/vokerg/voktrader/executor/ExecutorTransactionBoundaryBeanPostProcessor.java"
    );

    @Test
    void allLiveRoutersReachTransactionalOutboxAcceptance() throws IOException {
        String routerSource = Files.readString(EXECUTION_ROUTER);
        String gatewaySource = Files.readString(LIVE_GATEWAY);

        assertThat(routerSource).contains("LiveOrderGateway");
        assertThat(routerSource).contains("liveOrderGateway.submitOrder(");
        assertThat(routerSource).doesNotContain("liveExecutionService.execute(");
        assertThat(gatewaySource).contains("DurableOrderAcceptanceService");
        assertThat(gatewaySource).contains("acceptanceService.accept(");
        assertThat(gatewaySource).doesNotContain("orderManager.submitOrder(");
        assertThat(gatewaySource).doesNotContain("PythonExecutorClient");
    }

    @Test
    void transactionalAcceptanceCannotDependOnExecutorSubmission() throws IOException {
        for (Path sourcePath : TRANSACTIONAL_ACCEPTANCE_SOURCES) {
            String source = Files.readString(sourcePath);
            assertThat(source)
                    .as("transactional acceptance source %s must not import or invoke the executor", sourcePath)
                    .doesNotContain("PythonExecutorClient", ".submit(");
        }
    }

    @Test
    void outboxWorkerOwnsTheProductionSubmitCall() throws IOException {
        String workerSource = Files.readString(WORKER);

        assertThat(workerSource).contains("executorClient.submit(command)");
    }

    @Test
    void allExecutorNetworkCallsSuspendAmbientTransactions() throws IOException {
        String source = Files.readString(NETWORK_BOUNDARY);

        assertThat(source).contains("PythonExecutorClient");
        assertThat(source).contains("TransactionDefinition.PROPAGATION_NOT_SUPPORTED");
        assertThat(source).contains("TransactionInterceptor");
    }
}
