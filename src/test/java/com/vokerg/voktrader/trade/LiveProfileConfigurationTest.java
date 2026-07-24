package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class LiveProfileConfigurationTest {
    @Test
    void liveProfileIsCapabilityOnlyAndDoesNotAutoArm() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application-live.properties")) {
            assertThat(input).as("application-live.properties").isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("voktrader.trading.mode")).isEqualTo("LIVE");
        assertThat(properties.getProperty("voktrader.trading.live-enabled")).isEqualTo("true");
        assertThat(properties.getProperty("voktrader.trading.kill-switch-enabled")).isEqualTo("true");
        assertThat(properties.getProperty("voktrader.trading.expected-account-id"))
                .isEqualTo("${VOKTRADER_EXPECTED_ACCOUNT_ID:}");
        assertThat(properties.getProperty("voktrader.trading.live-arm-ttl"))
                .isEqualTo("${VOKTRADER_LIVE_ARM_TTL:15m}");
        assertThat(properties.getProperty("voktrader.executor.api-token"))
                .isEqualTo("${VOKTRADER_EXECUTOR_API_TOKEN:change-me}");
    }
}
