package com.vokerg.voktrader.executor;

import java.util.List;

public record ExecutorFillsResponse(
        boolean success,
        List<ExecutorFillResponse> fills,
        String rawResponse,
        ExecutorErrorResponse error
) {
    public ExecutorFillsResponse {
        fills = fills == null ? List.of() : List.copyOf(fills);
    }

    public static ExecutorFillsResponse failure(String type, String message) {
        return new ExecutorFillsResponse(false, List.of(), null, new ExecutorErrorResponse(type, message));
    }
}
