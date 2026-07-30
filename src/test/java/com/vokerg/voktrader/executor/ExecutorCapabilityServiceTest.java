package com.vokerg.voktrader.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutorCapabilityServiceTest {
    private final PythonExecutorClient client = mock(PythonExecutorClient.class);
    private final ExecutorCapabilityService service = new ExecutorCapabilityService(client);

    @Test
    void compatibleEvidencePassesPreflight() {
        when(client.capabilities()).thenReturn(capabilities(
                true,
                "executor-api-v1",
                "0.3.0",
                "py-clob-client-v2",
                "1.1.0",
                List.of("FOK", "FAK", "GTC", "GTD"),
                null
        ));

        ExecutorCapabilityService.ExecutorCapabilityReport report = service.report();

        assertThat(report.evidenceAvailable()).isTrue();
        assertThat(report.compatible()).isTrue();
        assertThat(report.blockers()).isEmpty();
    }

    @Test
    void missingOrDriftedEvidenceFailsClosedWithEveryBlocker() {
        when(client.capabilities()).thenReturn(capabilities(
                false,
                "executor-api-v2",
                "UNKNOWN",
                "unexpected-sdk",
                "UNKNOWN",
                List.of("FOK", "GTC"),
                new ExecutorErrorResponse("CAPABILITY_EVIDENCE_UNAVAILABLE", "installed package metadata unavailable")
        ));

        ExecutorCapabilityService.ExecutorCapabilityReport report = service.report();

        assertThat(report.evidenceAvailable()).isFalse();
        assertThat(report.compatible()).isFalse();
        assertThat(report.blockers())
                .contains("installed package metadata unavailable")
                .anyMatch(blocker -> blocker.contains("protocol mismatch"))
                .contains("executor package version is unavailable")
                .anyMatch(blocker -> blocker.contains("SDK package mismatch"))
                .contains("executor SDK version is unavailable")
                .anyMatch(blocker -> blocker.contains("FAK") && blocker.contains("GTD"));
    }

    private ExecutorCapabilitiesResponse capabilities(
            boolean success,
            String protocolVersion,
            String executorVersion,
            String sdkPackage,
            String sdkVersion,
            List<String> supportedTimeInForce,
            ExecutorErrorResponse error
    ) {
        return new ExecutorCapabilitiesResponse(
                success,
                protocolVersion,
                executorVersion,
                sdkPackage,
                sdkVersion,
                supportedTimeInForce,
                List.of(),
                List.of(),
                false,
                false,
                new BigDecimal("5.00"),
                error
        );
    }
}
