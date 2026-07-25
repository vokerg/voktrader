package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CentralEntryRiskArchitectureTest {
    @Test
    void onlyTheTypedBoundaryMayInvokeCentralEntryPolicy() throws IOException {
        Path root = Path.of("src/main/java");
        try (var paths = Files.walk(root)) {
            List<Path> callers = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "riskCheckService.assessEntry("))
                    .toList();

            assertThat(callers)
                    .extracting(path -> path.getFileName().toString())
                    .containsExactly("StrategyIntentBoundary.java");
        }
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
    void orderLayerGatewayRejectsBuyWithoutApprovalContext() throws IOException {
        String gateway = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/LiveOrderGateway.java"));

        assertThat(gateway)
                .contains("intent.side() == TradeSide.BUY")
                .contains("EntryRiskDecisionContext.approves(intent, mode)");
    }

    private boolean contains(Path source, String needle) {
        try {
            return Files.readString(source).contains(needle);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect " + source, ex);
        }
    }
}
