package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class LiveProfileConfigurationTest {
    @Test
    void liveProfileIsCapabilityOnlyLocalAndDoesNotAutoArm() throws IOException {
        Properties properties = loadLiveProperties();

        assertThat(properties.getProperty("server.address"))
                .isEqualTo("${VOKTRADER_LIVE_BIND_ADDRESS:127.0.0.1}");
        assertThat(properties.getProperty("spring.h2.console.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("spring.boot.admin.server.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("voktrader.control-plane.read-only-token"))
                .isEqualTo("${VOKTRADER_CONTROL_READ_ONLY_TOKEN}");
        assertThat(properties.getProperty("voktrader.control-plane.operator-token"))
                .isEqualTo("${VOKTRADER_CONTROL_OPERATOR_TOKEN}");
        assertThat(properties.getProperty("voktrader.control-plane.admin-token"))
                .isEqualTo("${VOKTRADER_CONTROL_ADMIN_TOKEN}");
        assertThat(properties.getProperty("voktrader.control-plane.confirmation-token"))
                .isEqualTo("${VOKTRADER_CONTROL_CONFIRMATION_TOKEN}");

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

    @Test
    void liveProfileKeepsFlywayAuthoritativeAndHibernateSchemaReadOnly() throws IOException {
        Properties properties = loadLiveProperties();

        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("spring.flyway.validate-on-migrate")).isEqualTo("true");
        assertThat(properties.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
        assertThat(properties.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    private Properties loadLiveProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application-live.properties")) {
            assertThat(input).as("application-live.properties").isNotNull();
            properties.load(input);
        }
        return properties;
    }
}
