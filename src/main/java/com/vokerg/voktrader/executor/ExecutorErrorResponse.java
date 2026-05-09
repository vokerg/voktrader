package com.vokerg.voktrader.executor;

public record ExecutorErrorResponse(
        String type,
        String message
) {
}
