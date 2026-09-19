package com.vokerg.voktrader.polymarket.user;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserWebSocketHealthServiceTest {
    @Test
    void connectionGenerationAdvancesAcrossReconnects() {
        UserWebSocketProperties properties = new UserWebSocketProperties();
        properties.setEnabled(true);
        properties.setApiKey("key");
        properties.setApiSecret("secret");
        properties.setApiPassphrase("passphrase");
        UserWebSocketHealthService service = new UserWebSocketHealthService(properties);

        Instant first = Instant.parse("2026-09-19T15:00:00Z");
        assertThat(service.onConnected(first)).isEqualTo(1L);
        assertThat(service.snapshot().healthy()).isTrue();

        service.onDisconnected(first.plusSeconds(5), new IllegalStateException("closed"));
        assertThat(service.snapshot().healthy()).isFalse();
        assertThat(service.snapshot().state()).isEqualTo(UserWebSocketHealthService.State.DISCONNECTED);

        assertThat(service.onConnected(first.plusSeconds(7))).isEqualTo(2L);
        assertThat(service.snapshot().generation()).isEqualTo(2L);
        assertThat(service.snapshot().healthy()).isTrue();
    }

    @Test
    void incompleteCredentialsAreExposedOnlyAsConfigurationState() {
        UserWebSocketProperties properties = new UserWebSocketProperties();
        properties.setEnabled(true);
        properties.setApiKey("key");
        UserWebSocketHealthService service = new UserWebSocketHealthService(properties);

        service.markConfigurationBlocked("authenticated user websocket credentials are not fully configured");

        assertThat(service.snapshot().credentialsConfigured()).isFalse();
        assertThat(service.snapshot().state()).isEqualTo(UserWebSocketHealthService.State.CONFIGURATION_BLOCKED);
    }
}
