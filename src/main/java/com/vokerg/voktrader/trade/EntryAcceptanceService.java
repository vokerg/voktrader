package com.vokerg.voktrader.trade;

/** Sole strategy-facing boundary for new-position entry requests. */
@FunctionalInterface
public interface EntryAcceptanceService {
    TradeExecutionResult accept(EntryIntent intent);
}
