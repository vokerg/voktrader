package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalTickBackfillArchitectureTest {
    private static final List<Path> BACKFILL_SOURCES = List.of(
            Path.of("src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageService.java"),
            Path.of("src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickEvidenceImportRunner.java"),
            Path.of("src/main/java/com/vokerg/voktrader/marketdata/model/HistoricalTickCoverageEntity.java")
    );

    @Test
    void backfillSourcesCannotIntroduceAnImplicitCentTick() throws IOException {
        for (Path source : BACKFILL_SOURCES) {
            String content = Files.readString(source);
            assertThat(content)
                    .as("%s must require sourced tick evidence", source)
                    .doesNotContain("new BigDecimal(\"0.01\")")
                    .doesNotContain("BigDecimal.valueOf(0.01)")
                    .doesNotContain("orElse(\"0.01\")")
                    .doesNotContain("orElseGet(() -> \"0.01\")");
        }
    }
}
