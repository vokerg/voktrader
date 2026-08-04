package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CentralEntryRiskArchitectureTest {
    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void onlyTheTypedBoundaryMayInvokeCentralEntryPolicy() throws IOException {
        assertOnlyCaller(
                "riskCheckService.assessEntry(",
                "StrategyIntentBoundary.java",
                "central entry policy must have exactly one production caller"
        );
    }

    @Test
    void onlyGuardedGatewayMaySubmitThroughOrderManager() throws IOException {
        assertOnlyCaller(
                "orderManager.submitOrder(",
                "LiveOrderGateway.java",
                "production entry routes must not bypass the guarded order gateway"
        );
    }

    @Test
    void compatibilityExecutorsMayOnlyAssertAnExistingDecision() throws IOException {
        String paper = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/paper/PaperExecutionService.java"));
        String live = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/LiveExecutionService.java"));

        assertThat(paper).doesNotContain("assessEntry(");
        assertThat(live).doesNotContain("assessEntry(");
        assertThat(paper).contains("riskCheckService.assess(");
        assertThat(live).contains("riskCheckService.assess(");
    }

    @Test
    void compatibilityAndPrimaryOrderRoutersRejectBuyWithoutApprovalContext() throws IOException {
        String compatibilityRouter = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/ExecutionRouter.java"));
        String primaryOrderGateway = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/RoutingOrderGateway.java"));
        String liveOrderGateway = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/LiveOrderGateway.java"));

        assertApprovalGuard(compatibilityRouter);
        assertApprovalGuard(primaryOrderGateway);
        assertApprovalGuard(liveOrderGateway);
    }

    @Test
    void concretePaperSubmissionIsOnlyAvailableThroughThePrimaryRouter() throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE_ROOT)) {
            List<String> files = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "PaperOrderGateway"))
                    .filter(path -> contains(path, "submitOrder("))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(files)
                    .as("only the paper adapter implementation and guarded primary router may expose paper submission; read-state and advancement wiring may reference the adapter without submission")
                    .containsExactly("PaperOrderGateway.java", "RoutingOrderGateway.java");
        }
    }

    private void assertApprovalGuard(String source) {
        assertThat(source)
                .contains("intent.side() == TradeSide.BUY")
                .contains("EntryRiskDecisionContext.approves(intent, mode)");
    }

    private void assertOnlyCaller(String needle, String expectedFile, String description) throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE_ROOT)) {
            List<Path> callers = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, needle))
                    .toList();

            assertThat(callers)
                    .as(description)
                    .extracting(path -> path.getFileName().toString())
                    .containsExactly(expectedFile);
        }
    }

    private boolean contains(Path source, String needle) {
        try {
            return Files.readString(source).contains(needle);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect " + source, ex);
        }
    }
}
