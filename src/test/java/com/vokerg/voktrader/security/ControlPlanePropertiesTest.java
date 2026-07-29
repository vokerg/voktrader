package com.vokerg.voktrader.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlanePropertiesTest {
    @Test
    void validDistinctSecretsResolveTheExpectedRolesAndConfirmation() {
        ControlPlaneProperties properties = validProperties();

        properties.validateForLive();

        assertThat(properties.authenticate("readonly-token-000000000000000001"))
                .isEqualTo(ControlPlaneProperties.ControlPlaneRole.READ_ONLY);
        assertThat(properties.authenticate("operator-token-000000000000000001"))
                .isEqualTo(ControlPlaneProperties.ControlPlaneRole.OPERATOR);
        assertThat(properties.authenticate("administrator-token-00000000000001"))
                .isEqualTo(ControlPlaneProperties.ControlPlaneRole.ADMIN);
        assertThat(properties.authenticate("wrong-token")).isNull();
        assertThat(properties.confirmationMatches("confirmation-token-000000000000001")).isTrue();
        assertThat(properties.confirmationMatches("wrong-confirmation")).isFalse();
    }

    @Test
    void missingOrShortSecretsFailLiveStartupClosed() {
        ControlPlaneProperties missing = validProperties();
        missing.setOperatorToken(" ");
        assertThatThrownBy(missing::validateForLive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator token is required");

        ControlPlaneProperties shortSecret = validProperties();
        shortSecret.setConfirmationToken("too-short");
        assertThatThrownBy(shortSecret::validateForLive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation token must be at least 32 characters");
    }

    @Test
    void duplicateSecretsFailLiveStartupClosed() {
        ControlPlaneProperties properties = validProperties();
        properties.setAdminToken(properties.getOperatorToken());

        assertThatThrownBy(properties::validateForLive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be distinct");
    }

    private ControlPlaneProperties validProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setReadOnlyToken("readonly-token-000000000000000001");
        properties.setOperatorToken("operator-token-000000000000000001");
        properties.setAdminToken("administrator-token-00000000000001");
        properties.setConfirmationToken("confirmation-token-000000000000001");
        return properties;
    }
}
