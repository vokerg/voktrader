package com.vokerg.voktrader.trade;

/**
 * Typed risk-reducing boundary for cancelling an existing order.
 *
 * Strategies may request cancellation without gaining access to generic order
 * submission or executor routing.
 */
public interface CancellationSubmissionService {
    OrderLifecycleResult cancelOrder(String localOrderId, String reason);
}
