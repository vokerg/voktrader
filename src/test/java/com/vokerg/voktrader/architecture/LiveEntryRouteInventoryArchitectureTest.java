package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveEntryRouteInventoryArchitectureTest {
    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void remoteSubmitCallersAreExhaustivelyInventoried() throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE_ROOT)) {
            List<String> callers = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "pythonExecutorClient.submit("))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(callers)
                    .as("adding a new remote submit caller must extend the kill-switch route matrix")
                    .containsExactly("LiveExecutionService.java", "OrderManager.java");
        }
    }

    @Test
    void legacyProductionAdapterUsesTypedEntryAndExitBoundaries() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/trade/LegacyStrategyIntentAdapter.java"));

        assertThat(source)
                .contains("private final EntryAcceptanceService entryAcceptanceService")
                .contains("private final ExitSubmissionService exitSubmissionService")
                .contains("return entryAcceptanceService.accept(intent)")
                .contains("return exitSubmissionService.submit(intent)");
    }

    @Test
    void apiLayerHasNoManualNewPositionSubmissionSurface() throws IOException {
        Path apiRoot = Path.of("src/main/java/com/vokerg/voktrader/api");
        try (var paths = Files.walk(apiRoot)) {
            List<String> entrySurfaces = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsAny(
                            path,
                            "EntryIntent.buy(",
                            "TradeIntent.buy(",
                            "pythonExecutorClient.submit(",
                            ".submitOrder("
                    ))
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertThat(entrySurfaces)
                    .as("a new API/manual BUY route must be added to the exhaustive kill-switch matrix")
                    .isEmpty();
        }
    }

    @Test
    void outboxSubmitRouteDoesNotExistBeforeT020AndT021() throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE_ROOT)) {
            List<String> outboxSubmitters = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return (name.contains("outbox") || name.contains("submissionworker"))
                                && contains(path, "submit(");
                    })
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertThat(outboxSubmitters)
                    .as("T020/T021 must extend the kill-switch route matrix when an outbox submit worker is introduced")
                    .isEmpty();
        }
    }

    private boolean containsAny(Path path, String... needles) {
        for (String needle : needles) {
            if (contains(path, needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(Path path, String needle) {
        try {
            return Files.readString(path).contains(needle);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect " + path, ex);
        }
    }
}
