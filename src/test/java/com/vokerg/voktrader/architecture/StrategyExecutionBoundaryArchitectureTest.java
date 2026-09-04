package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyExecutionBoundaryArchitectureTest {
    private static final List<String> FORBIDDEN_STRATEGY_DEPENDENCIES = List.of(
            "com.vokerg.voktrader.trade.TradeIntent",
            "com.vokerg.voktrader.trade.ExecutionRouter",
            "com.vokerg.voktrader.trade.OrderGateway",
            "com.vokerg.voktrader.executor.PythonExecutorClient"
    );

    @Test
    void strategySourcesCannotSelectExecutionPlumbing() throws IOException {
        Path strategyRoot = Path.of("src/main/java/com/vokerg/voktrader/strategy");

        try (var paths = Files.walk(strategyRoot)) {
            List<Path> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::containsForbiddenDependency)
                    .toList();

            assertThat(violations)
                    .as("strategy sources must depend on typed intent APIs, not generic routing or executor plumbing")
                    .isEmpty();
        }
    }

    private boolean containsForbiddenDependency(Path source) {
        try {
            String content = Files.readString(source);
            return FORBIDDEN_STRATEGY_DEPENDENCIES.stream().anyMatch(content::contains);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect " + source, ex);
        }
    }
}
