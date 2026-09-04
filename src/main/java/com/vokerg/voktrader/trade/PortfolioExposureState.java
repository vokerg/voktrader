package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.TradeStatus;

/**
 * Portfolio-facing exposure classification used before the settlement ledger is introduced.
 * All three states reserve entry capacity because each can still produce or retain exposure.
 */
public enum PortfolioExposureState {
    PENDING_ENTRY,
    PROVISIONAL_POSITION,
    EXITING_POSITION;

    public static PortfolioExposureState from(TradeStatus status) {
        return switch (status) {
            case CREATED, ENTRY_PENDING -> PENDING_ENTRY;
            case PARTIALLY_OPEN, OPEN -> PROVISIONAL_POSITION;
            case EXIT_PENDING, PARTIALLY_CLOSED -> EXITING_POSITION;
            default -> null;
        };
    }
}
