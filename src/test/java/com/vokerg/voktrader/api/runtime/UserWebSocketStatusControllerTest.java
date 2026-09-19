package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.polymarket.user.UserWebSocketHealthService;
import com.vokerg.voktrader.polymarket.user.UserWebSocketSafetyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserWebSocketStatusControllerTest {
    @Test
    void statusExposesHealthGenerationAndExposureWithoutCredentials() {
        UserWebSocketHealthService health = mock(UserWebSocketHealthService.class);
        UserWebSocketSafetyService safety = mock(UserWebSocketSafetyService.class);
        when(health.snapshot()).thenReturn(new UserWebSocketHealthService.Snapshot(
                true,
                true,
                true,
                UserWebSocketHealthService.State.HEALTHY,
                5L,
                Instant.parse("2026-09-19T15:00:00Z"),
                Instant.parse("2026-09-19T15:00:10Z"),
                Instant.parse("2026-09-19T15:00:09Z"),
                null,
                null
        ));
        when(safety.exposureSnapshot()).thenReturn(
                new UserWebSocketSafetyService.ExposureSnapshot(true, false)
        );

        UserWebSocketStatusController.UserWebSocketStatus response =
                new UserWebSocketStatusController(health, safety).status();

        assertThat(response.health().healthy()).isTrue();
        assertThat(response.health().generation()).isEqualTo(5L);
        assertThat(response.health().credentialsConfigured()).isTrue();
        assertThat(response.exposure().requiresHealthyStream()).isTrue();
    }
}
