package com.vokerg.voktrader.polymarket.user;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class UserWebSocketHealthService {
    public enum State {
        DISABLED,
        CONFIGURATION_BLOCKED,
        CONNECTING,
        HEALTHY,
        DISCONNECTED,
        STOPPED
    }

    private final UserWebSocketProperties properties;
    private State state;
    private long generation;
    private Instant lastConnectedAt;
    private Instant lastHeartbeatAt;
    private Instant lastEventAt;
    private Instant lastDisconnectAt;
    private String lastDisconnectReason;

    public UserWebSocketHealthService(UserWebSocketProperties properties) {
        this.properties = properties;
        this.state = properties.isEnabled() ? State.CONNECTING : State.DISABLED;
    }

    public synchronized void markDisabled() {
        state = State.DISABLED;
    }

    public synchronized void markConfigurationBlocked(String reason) {
        state = State.CONFIGURATION_BLOCKED;
        lastDisconnectReason = reason;
    }

    public synchronized void markConnecting() {
        if (state != State.STOPPED) {
            state = State.CONNECTING;
        }
    }

    public synchronized long onConnected(Instant now) {
        generation++;
        state = State.HEALTHY;
        lastConnectedAt = now;
        lastHeartbeatAt = now;
        lastDisconnectReason = null;
        return generation;
    }

    public synchronized void onHeartbeat(Instant now) {
        if (state != State.STOPPED) {
            lastHeartbeatAt = now;
        }
    }

    public synchronized void onEvent(Instant now) {
        if (state != State.STOPPED) {
            lastEventAt = now;
            lastHeartbeatAt = now;
        }
    }

    public synchronized void onDisconnected(Instant now, Throwable cause) {
        if (state == State.STOPPED) {
            return;
        }
        state = State.DISCONNECTED;
        lastDisconnectAt = now;
        lastDisconnectReason = cause == null
                ? "socket disconnected"
                : safeReason(cause);
    }

    public synchronized void stop() {
        state = State.STOPPED;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                properties.isEnabled(),
                properties.credentialsConfigured(),
                state == State.HEALTHY,
                state,
                generation,
                lastConnectedAt,
                lastHeartbeatAt,
                lastEventAt,
                lastDisconnectAt,
                lastDisconnectReason
        );
    }

    private String safeReason(Throwable cause) {
        return cause.getClass().getSimpleName();
    }

    public record Snapshot(
            boolean enabled,
            boolean credentialsConfigured,
            boolean healthy,
            State state,
            long generation,
            Instant lastConnectedAt,
            Instant lastHeartbeatAt,
            Instant lastEventAt,
            Instant lastDisconnectAt,
            String lastDisconnectReason
    ) {
    }
}
