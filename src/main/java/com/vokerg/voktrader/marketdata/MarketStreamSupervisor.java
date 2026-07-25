package com.vokerg.voktrader.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic state machine for a single shared market-data stream.
 *
 * <p>It deliberately separates transport liveness from strategy readability: reconnects, detected
 * gaps, and stale heartbeats move the stream into a fail-closed state until a complete REST reseed
 * succeeds.</p>
 */
public final class MarketStreamSupervisor {

    public enum State {
        STARTING,
        HEALTHY,
        DISCONNECTED,
        GAP_DETECTED,
        STALE,
        RESEEDING,
        STOPPED
    }

    public enum MessageDecision {
        ACCEPT,
        PAUSED,
        RECONNECT_FOR_GAP
    }

    public enum PollAction {
        NONE,
        RECONNECT_STALE_STREAM,
        RESEED
    }

    public record Snapshot(
            State state,
            long generation,
            boolean strategyPaused,
            String pauseReason,
            Instant lastHeartbeatAt,
            Instant lastExchangeEventAt,
            long reconnectCount,
            long gapCount,
            long staleCount,
            long pauseCount,
            long reseedCount
    ) {
    }

    private final Duration heartbeatTimeout;
    private final Duration maxExchangeEventGap;

    private State state = State.STARTING;
    private long generation;
    private String pauseReason = "STARTING";
    private Instant lastHeartbeatAt;
    private Instant lastExchangeEventAt;
    private long reconnectCount;
    private long gapCount;
    private long staleCount;
    private long pauseCount;
    private long reseedCount;

    public MarketStreamSupervisor(Duration heartbeatTimeout, Duration maxExchangeEventGap) {
        this.heartbeatTimeout = positive(heartbeatTimeout, "heartbeatTimeout");
        this.maxExchangeEventGap = positive(maxExchangeEventGap, "maxExchangeEventGap");
    }

    public synchronized void markInitialSeedAttempted(Instant now) {
        requireNow(now);
        state = State.HEALTHY;
        pauseReason = null;
        lastHeartbeatAt = now;
    }

    public synchronized long onConnected(Instant now) {
        requireNow(now);
        generation++;
        lastHeartbeatAt = now;
        lastExchangeEventAt = null;

        if (generation == 1 && state == State.HEALTHY) {
            return generation;
        }

        reconnectCount++;
        pause(State.RESEEDING, "RECONNECT_RESEED");
        return generation;
    }

    public synchronized void onHeartbeat(Instant now) {
        requireNow(now);
        if (state != State.STOPPED) {
            lastHeartbeatAt = now;
        }
    }

    public synchronized void onDisconnected(Instant now, Throwable cause) {
        requireNow(now);
        if (state == State.STOPPED) {
            return;
        }
        String detail = cause == null || cause.getMessage() == null
                ? "SOCKET_DISCONNECTED"
                : "SOCKET_DISCONNECTED: " + cause.getMessage();
        pause(State.DISCONNECTED, detail);
    }

    public synchronized MessageDecision onMarketMessage(Instant exchangeEventAt, Instant receivedAt) {
        requireNow(receivedAt);
        onHeartbeat(receivedAt);
        if (state != State.HEALTHY) {
            return MessageDecision.PAUSED;
        }
        if (exchangeEventAt == null) {
            return MessageDecision.ACCEPT;
        }
        if (lastExchangeEventAt != null) {
            Duration delta = Duration.between(lastExchangeEventAt, exchangeEventAt);
            if (delta.compareTo(maxExchangeEventGap.negated()) < 0
                    || delta.compareTo(maxExchangeEventGap) > 0) {
                gapCount++;
                pause(State.GAP_DETECTED, delta.isNegative() ? "EVENT_TIME_REGRESSION" : "EVENT_TIME_GAP");
                return MessageDecision.RECONNECT_FOR_GAP;
            }
        }
        lastExchangeEventAt = exchangeEventAt;
        return MessageDecision.ACCEPT;
    }

    public synchronized PollAction poll(Instant now) {
        requireNow(now);
        if (state == State.RESEEDING) {
            return PollAction.RESEED;
        }
        if (state != State.HEALTHY || lastHeartbeatAt == null) {
            return PollAction.NONE;
        }
        Duration heartbeatAge = Duration.between(lastHeartbeatAt, now);
        if (heartbeatAge.compareTo(heartbeatTimeout) > 0) {
            staleCount++;
            pause(State.STALE, "HEARTBEAT_TIMEOUT");
            return PollAction.RECONNECT_STALE_STREAM;
        }
        return PollAction.NONE;
    }

    public synchronized void onReconnectStarted(Instant now, String reason) {
        requireNow(now);
        if (state == State.STOPPED) {
            return;
        }
        if (state == State.HEALTHY) {
            pause(State.DISCONNECTED, reason == null ? "RECONNECT_REQUESTED" : reason);
        }
    }

    public synchronized void onReseedSucceeded(Instant now) {
        requireNow(now);
        if (state == State.STOPPED) {
            return;
        }
        reseedCount++;
        state = State.HEALTHY;
        pauseReason = null;
        lastHeartbeatAt = now;
        lastExchangeEventAt = null;
    }

    public synchronized void stop() {
        state = State.STOPPED;
        pauseReason = "STOPPED";
    }

    public synchronized boolean strategyReadable() {
        return state == State.HEALTHY;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                state,
                generation,
                state != State.HEALTHY,
                pauseReason,
                lastHeartbeatAt,
                lastExchangeEventAt,
                reconnectCount,
                gapCount,
                staleCount,
                pauseCount,
                reseedCount
        );
    }

    private void pause(State nextState, String reason) {
        if (state == State.HEALTHY) {
            pauseCount++;
        }
        state = nextState;
        pauseReason = reason;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void requireNow(Instant now) {
        Objects.requireNonNull(now, "now");
    }
}
