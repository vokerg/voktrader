package com.vokerg.voktrader.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicTickMetadataArchitectureTest {
    @Test
    void strategyRoundingUsesTheCentralTickService() throws IOException {
        String source = source("src/main/java/com/vokerg/voktrader/strategy/v2/StrategyV2OrderActionBuilder.java");

        assertThat(source).contains("tickSizeService.requireTickSize");
        assertThat(source).contains("tickSizeService.round");
        assertThat(source).doesNotContain("new BigDecimal(\"0.01\")");
    }

    @Test
    void liveMetadataComesFromRestAndWebsocketProtocol() throws IOException {
        String restClient = source("src/main/java/com/vokerg/voktrader/polymarket/client/ClobClient.java");
        String websocketClient = source("src/main/java/com/vokerg/voktrader/polymarket/client/PolymarketWebSocketClient.java");
        String message = source("src/main/java/com/vokerg/voktrader/polymarket/dto/MarketWsMessageDto.java");

        assertThat(restClient).contains("recordRestBook");
        assertThat(websocketClient).contains("recordTickSizeChange");
        assertThat(message).contains("old_tick_size", "new_tick_size", "tick_size_change");
    }

    @Test
    void executorHttpCannotRunBeforeTickValidation() throws IOException {
        String source = source("src/main/java/com/vokerg/voktrader/executor/PythonExecutorClient.java");

        int validation = source.indexOf("tickSizeService.validate");
        int httpPost = source.indexOf(".post()");
        assertThat(validation).isGreaterThanOrEqualTo(0);
        assertThat(httpPost).isGreaterThan(validation);
        assertThat(source).contains("rejected before HTTP");
    }

    @Test
    void replayUsesTimestampedMetadataInsteadOfTheLiveCache() throws IOException {
        String service = source("src/main/java/com/vokerg/voktrader/marketdata/TickSizeService.java");
        String timeMachine = source("src/main/java/com/vokerg/voktrader/time/TimeMachine.java");

        assertThat(service).contains("TimeMachine.isOverridden()", "EffectiveAtLessThanEqual");
        assertThat(timeMachine).contains("public static boolean isOverridden()");
    }

    private String source(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
