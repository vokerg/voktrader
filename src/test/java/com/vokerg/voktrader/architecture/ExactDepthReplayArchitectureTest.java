package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExactDepthReplayArchitectureTest {
    @Test
    void backtestReplayCannotRecreateSyntheticOneLevelBooksFromSummaryRows() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/backtest/BacktestReplayService.java"
        ));

        assertThat(source)
                .contains("marketDepthReplayService.loadExactSnapshot")
                .contains("booksByTokenId().values()")
                .doesNotContain("levels(row.getBestBid(), row.getBidDepth())")
                .doesNotContain("levels(row.getBestAsk(), row.getAskDepth())")
                .doesNotContain("new DepthKey(");
    }
}
