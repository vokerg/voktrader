package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeSide;
import org.springframework.stereotype.Service;

@Service
public class LiveOrderGateway implements OrderGateway {
    private final DurableOrderAcceptanceService acceptanceService;
    private final DurableOrderCancellationService cancellationService;

    public LiveOrderGateway(
            DurableOrderAcceptanceService acceptanceService,
            DurableOrderCancellationService cancellationService
    ) {
        this.acceptanceService = acceptanceService;
        this.cancellationService = cancellationService;
    }

    @Override
    public OrderLifecycleResult submitOrder(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        if (intent.side() == TradeSide.BUY && !EntryRiskDecisionContext.approves(intent, mode)) {
            String message = "BUY rejected: approved central entry risk decision is required";
            return new OrderLifecycleResult(false, null, null, null, null, null, null, message, message);
        }
        return acceptanceService.accept(intent, mode, riskDecisionId(intent, mode));
    }

    @Override
    public OrderLifecycleResult cancelOrder(String localOrderId, String reason) {
        return cancellationService.cancel(localOrderId, reason);
    }

    private String riskDecisionId(TradeIntent intent, ExecutionMode mode) {
        return EntryRiskDecisionContext.current()
                .filter(decision -> decision.request().matches(intent, mode))
                .map(EntryRiskDecisionContext.Decision::assessment)
                .map(RiskAssessment::correlationId)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> "lifecycle:"
                        + mode + ":"
                        + (intent.botId() == null ? "default" : intent.botId()) + ":"
                        + intent.strategyId() + ":"
                        + intent.marketId() + ":"
                        + intent.tokenId() + ":"
                        + intent.side() + ":"
                        + intent.decisionAt().toEpochMilli());
    }
}
