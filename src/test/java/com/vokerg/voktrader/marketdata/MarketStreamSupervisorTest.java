package com.vokerg.voktrader.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketStreamSupervisorTest {

    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration MAX_EVENT_GAP = Duration.ofSeconds(30);

    @Test
    void thirtyMinuteHeartbeatSoakStaysHealthy() {
        MarketStreamSupervisor supervisor = supervisor();
        Instant start = Instant.parse("2026-07-25T12:00:00Z");
        supervisor.markInitialSeedAttempted(start);
        assertEquals(1L, supervisor.onConnected(start));

        for (int seconds = 10; seconds <= 30 * 60; seconds += 10) {
            Instant heartbeatAt = start.plusSeconds(seconds);
            supervisor.onHeartbeat(heartbeatAt);
            assertEquals(MarketStreamSupervisor.PollAction.NONE, supervisor.poll(heartbeatAt.plusSeconds(9)));
        }

        MarketStreamSupervisor.Snapshot snapshot = supervisor.snapshot();
        assertEquals(MarketStreamSupervisor.State.HEALTHY, snapshot.state());
        assertEquals(0L, snapshot.staleCount());
        assertFalse(snapshot.strategyPaused());
    }

    @Test
    void reconnectPausesUntilFreshRestReseedCompletes() {
        MarketStreamSupervisor supervisor = supervisor();
        Instant start = Instant.parse("2026-07-25T12:00:00Z");
        supervisor.markInitialSeedAttempted(start);
        supervisor.onConnected(start);

        supervisor.onDisconnected(start.plusSeconds(5), new IllegalStateException("forced disconnect"));
        assertTrue(supervisor.snapshot().strategyPaused());
        assertEquals(2L, supervisor.onConnected(start.plusSeconds(7)));
        assertEquals(MarketStreamSupervisor.PollAction.RESEED, supervisor.poll(start.plusSeconds(8)));
        assertEquals(
                MarketStreamSupervisor.MessageDecision.PAUSED,
                supervisor.onMarketMessage(start.plusSeconds(8), start.plusSeconds(8))
        );

        supervisor.onReseedSucceeded(start.plusSeconds(9));

        assertEquals(MarketStreamSupervisor.State.HEALTHY, supervisor.snapshot().state());
        assertEquals(1L, supervisor.snapshot().reconnectCount());
        assertEquals(1L, supervisor.snapshot().reseedCount());
        assertEquals(
                MarketStreamSupervisor.MessageDecision.ACCEPT,
                supervisor.onMarketMessage(start.plusSeconds(10), start.plusSeconds(10))
        );
    }

    @Test
    void staleHeartbeatRequestsReconnectAndPausesStrategy() {
        MarketStreamSupervisor supervisor = supervisor();
        Instant start = Instant.parse("2026-07-25T12:00:00Z");
        supervisor.markInitialSeedAttempted(start);
        supervisor.onConnected(start);

        assertEquals(
                MarketStreamSupervisor.PollAction.RECONNECT_STALE_STREAM,
                supervisor.poll(start.plusSeconds(26))
        );
        assertEquals(MarketStreamSupervisor.State.STALE, supervisor.snapshot().state());
        assertTrue(supervisor.snapshot().strategyPaused());
        assertEquals(1L, supervisor.snapshot().staleCount());
    }

    @Test
    void exchangeTimestampGapForcesReconnect() {
        MarketStreamSupervisor supervisor = supervisor();
        Instant start = Instant.parse("2026-07-25T12:00:00Z");
        supervisor.markInitialSeedAttempted(start);
        supervisor.onConnected(start);

        assertEquals(
                MarketStreamSupervisor.MessageDecision.ACCEPT,
                supervisor.onMarketMessage(start.plusSeconds(1), start.plusSeconds(1))
        );
        assertEquals(
                MarketStreamSupervisor.MessageDecision.RECONNECT_FOR_GAP,
                supervisor.onMarketMessage(start.plusSeconds(45), start.plusSeconds(2))
        );
        assertEquals(MarketStreamSupervisor.State.GAP_DETECTED, supervisor.snapshot().state());
        assertEquals(1L, supervisor.snapshot().gapCount());
        assertTrue(supervisor.snapshot().strategyPaused());
    }

    @Test
    void exchangeTimestampRegressionForcesReconnect() {
        MarketStreamSupervisor supervisor = supervisor();
        Instant start = Instant.parse("2026-07-25T12:00:00Z");
        supervisor.markInitialSeedAttempted(start);
        supervisor.onConnected(start);
        supervisor.onMarketMessage(start.plusSeconds(10), start.plusSeconds(1));

        assertEquals(
                MarketStreamSupervisor.MessageDecision.RECONNECT_FOR_GAP,
                supervisor.onMarketMessage(start.minusSeconds(25), start.plusSeconds(2))
        );
        assertEquals("EVENT_TIME_REGRESSION", supervisor.snapshot().pauseReason());
    }

    private MarketStreamSupervisor supervisor() {
        return new MarketStreamSupervisor(HEARTBEAT_TIMEOUT, MAX_EVENT_GAP);
    }
}
