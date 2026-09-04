package com.vokerg.voktrader.executor;

import java.math.BigDecimal;
import java.util.List;

public record ExecutorCapabilitiesResponse(
        boolean success,
        String protocolVersion,
        String executorVersion,
        String sdkPackage,
        String sdkVersion,
        List<String> supportedTimeInForce,
        List<ExecutorOrderVariation> supportedVariations,
        List<ExecutorOrderVariation> unsupportedVariations,
        Boolean dryRun,
        Boolean requireFok,
        BigDecimal maxOrderAmountUsd,
        ExecutorErrorResponse error
) {
    public ExecutorCapabilitiesResponse {
        supportedTimeInForce = supportedTimeInForce == null ? List.of() : List.copyOf(supportedTimeInForce);
        supportedVariations = supportedVariations == null ? List.of() : List.copyOf(supportedVariations);
        unsupportedVariations = unsupportedVariations == null ? List.of() : List.copyOf(unsupportedVariations);
    }

    public static ExecutorCapabilitiesResponse failure(String type, String message) {
        return new ExecutorCapabilitiesResponse(
                false,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                new ExecutorErrorResponse(type, message)
        );
    }
}
