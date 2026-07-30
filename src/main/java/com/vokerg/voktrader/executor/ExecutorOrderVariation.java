package com.vokerg.voktrader.executor;

public record ExecutorOrderVariation(
        String timeInForce,
        boolean postOnly,
        String route,
        boolean immediateFillExpected,
        boolean canRestOnBook
) {
}
