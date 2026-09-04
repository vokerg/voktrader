package com.vokerg.voktrader.trade;

/** Strategy-facing boundary for exposure-reducing exit requests. */
@FunctionalInterface
public interface ExitSubmissionService {
    TradeExecutionResult submit(ExitIntent intent);
}
