package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorSubmissionTransactionBoundaryArchitectureTest {
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
    private static final Path DETACHED_CLIENT = Path.of(
            "src/main/java/com/vokerg/voktrader/executor/TransactionDetachedPythonExecutorClient.java"
    );

    @Test
    void liveGatewayRoutesSubmissionThroughTransactionalOutboxAcceptance() throws IOException {
        String source = Files.readString(LIVE_GATEWAY);

        assertThat(source).contains("DurableOrderAcceptanceService");
        assertThat(source).contains("acceptanceService.accept(");
        assertThat(source).doesNotContain("orderManager.submitOrder(");
        assertThat(source).doesNotContain("PythonExecutorClient");
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
        String source = Files.readString(DETACHED_CLIENT);

        assertThat(source).contains("@Primary");
        assertThat(source).contains("@Transactional(propagation = Propagation.NOT_SUPPORTED)");
    }
}
