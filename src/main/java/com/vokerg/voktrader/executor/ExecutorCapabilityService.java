package com.vokerg.voktrader.executor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ExecutorCapabilityService {
    public static final String EXPECTED_PROTOCOL_VERSION = "executor-api-v1";
    public static final String EXPECTED_SDK_PACKAGE = "py-clob-client-v2";
    private static final Set<String> REQUIRED_TIME_IN_FORCE = Set.of("FOK", "FAK", "GTC", "GTD");

    private final PythonExecutorClient executorClient;

    public ExecutorCapabilityService(PythonExecutorClient executorClient) {
        this.executorClient = executorClient;
    }

    public ExecutorCapabilityReport report() {
        ExecutorCapabilitiesResponse response = executorClient.capabilities();
        List<String> blockers = new ArrayList<>();

        if (response == null) {
            blockers.add("executor capability response is missing");
            return ExecutorCapabilityReport.unavailable(blockers);
        }
        if (!response.success()) {
            String message = response.error() != null && response.error().message() != null
                    ? response.error().message()
                    : "executor capability evidence is unavailable";
            blockers.add(message);
        }
        if (!EXPECTED_PROTOCOL_VERSION.equals(response.protocolVersion())) {
            blockers.add("executor protocol mismatch: expected " + EXPECTED_PROTOCOL_VERSION
                    + " but received " + safe(response.protocolVersion()));
        }
        if (isUnknown(response.executorVersion())) {
            blockers.add("executor package version is unavailable");
        }
        if (!EXPECTED_SDK_PACKAGE.equals(response.sdkPackage())) {
            blockers.add("executor SDK package mismatch: expected " + EXPECTED_SDK_PACKAGE
                    + " but received " + safe(response.sdkPackage()));
        }
        if (isUnknown(response.sdkVersion())) {
            blockers.add("executor SDK version is unavailable");
        }

        Set<String> supported = new LinkedHashSet<>(response.supportedTimeInForce());
        Set<String> missing = new LinkedHashSet<>(REQUIRED_TIME_IN_FORCE);
        missing.removeAll(supported);
        if (!missing.isEmpty()) {
            blockers.add("executor is missing required time-in-force capabilities: " + String.join(", ", missing));
        }

        return new ExecutorCapabilityReport(
                response.success(),
                blockers.isEmpty(),
                response.protocolVersion(),
                response.executorVersion(),
                response.sdkPackage(),
                response.sdkVersion(),
                List.copyOf(supported),
                List.copyOf(blockers)
        );
    }

    private boolean isUnknown(String value) {
        return value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "<missing>" : value;
    }

    public record ExecutorCapabilityReport(
            boolean evidenceAvailable,
            boolean compatible,
            String protocolVersion,
            String executorVersion,
            String sdkPackage,
            String sdkVersion,
            List<String> supportedTimeInForce,
            List<String> blockers
    ) {
        public ExecutorCapabilityReport {
            supportedTimeInForce = supportedTimeInForce == null ? List.of() : List.copyOf(supportedTimeInForce);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        static ExecutorCapabilityReport unavailable(List<String> blockers) {
            return new ExecutorCapabilityReport(
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    blockers
            );
        }
    }
}
