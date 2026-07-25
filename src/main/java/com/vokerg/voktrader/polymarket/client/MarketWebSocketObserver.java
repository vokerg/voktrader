package com.vokerg.voktrader.polymarket.client;

import com.vokerg.voktrader.polymarket.dto.MarketWsMessageDto;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Optional lifecycle observer for market WebSocket consumers.
 *
 * <p>The WebSocket client keeps the public {@link Consumer} API backwards compatible. A consumer
 * implementing this interface additionally receives connection and raw-payload liveness events,
 * including PONG frames that are not mapped to market DTOs.</p>
 */
public interface MarketWebSocketObserver extends Consumer<MarketWsMessageDto> {

    default void onConnected() {
    }

    default void onHeartbeat(Instant receivedAt) {
    }

    default void onDisconnected(Throwable cause) {
    }
}
