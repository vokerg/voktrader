package com.vokerg.voktrader.marketdata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebSocketProtocolArchitectureTest {

    @Test
    void clientImplementsCurrentTextHeartbeatContract() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/polymarket/client/PolymarketWebSocketClient.java"
        ));

        assertTrue(source.contains("session.textMessage(\"PING\")"));
        assertTrue(source.contains("ping-interval-ms:10000"));
        assertTrue(source.contains("\"PONG\".equalsIgnoreCase"));
    }

    @Test
    void feedRequiresStrictReseedBeforeReadGateReopens() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/vokerg/voktrader/marketdata/MarketPriceFeedService.java"
        ));

        assertTrue(source.contains("seedStateFromRestOrderBooks(true)"));
        assertTrue(source.contains("supervisor.onReseedSucceeded"));
        assertTrue(source.contains("new LatestPriceState(supervisor::strategyReadable)"));
        assertTrue(source.contains("new OrderBookState(supervisor::strategyReadable)"));
    }
}
