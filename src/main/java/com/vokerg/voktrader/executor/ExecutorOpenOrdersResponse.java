package com.vokerg.voktrader.executor;

import java.util.List;

public record ExecutorOpenOrdersResponse(
        boolean success,
        List<ExecutorOrderStatusResponse> orders,
        String rawResponse,
        ExecutorErrorResponse error
) {
    public ExecutorOpenOrdersResponse {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    public static ExecutorOpenOrdersResponse failure(String type, String message) {
        return new ExecutorOpenOrdersResponse(false, List.of(), null, new ExecutorErrorResponse(type, message));
    }
}
