package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.strategy.v2.StrategyV2ExecutionProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Sole entry boundary: evaluates and persists entry risk before selecting an
 * execution adapter. Exit routing is intentionally separate and does not reuse
 * entry exposure gates.
 */
@Service
public class StrategyIntentBoundary implements EntryAcceptanceService, ExitSubmissionService {
    private final StrategyV2ExecutionProperties executionProperties;
    private final ExecutionRouter executionRouter;
    private final OrderGateway orderGateway;
    private final TradingProperties tradingProperties;
    private final RiskCheckService riskCheckService;
    private final TradeRiskCheckRepository riskCheckRepository;

    public StrategyIntentBoundary(
            StrategyV2ExecutionProperties executionProperties,
            ExecutionRouter executionRouter,
            OrderGateway orderGateway,
            TradingProperties tradingProperties,
            RiskCheckService riskCheckService,
            TradeRiskCheckRepository riskCheckRepository
    ) {
        this.executionProperties = executionProperties;
        this.executionRouter = executionRouter;
        this.orderGateway = orderGateway;
        this.tradingProperties = tradingProperties;
        this.riskCheckService = riskCheckService;
        this.riskCheckRepository = riskCheckRepository;
    }

    @Override
    public TradeExecutionResult accept(EntryIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        ExecutionMode mode = configuredMode();
        EntryRiskRequest request = EntryRiskRequest.of(intent, mode);
        RiskAssessment assessment = riskCheckService.assessEntry(request);
        riskCheckRepository.saveAll(assessment.checks());
        if (!assessment.passed()) {
            return TradeExecutionResult.rejected(mode, null, null, null, null, assessment.firstBlockMessage());
        }
        return EntryRiskDecisionContext.withApproved(
                request,
                assessment,
                () -> route(intent.tradeIntent(), intent.owner(), mode)
        );
    }

    @Override
    public TradeExecutionResult submit(ExitIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        return route(intent.tradeIntent(), intent.owner(), configuredMode());
    }

    private TradeExecutionResult route(TradeIntent intent, StrategyInstanceKey owner, ExecutionMode mode) {
        if (executionProperties.isUseOrderLayer()) {
            OrderGateway gateway = OrderGatewayContext.current().orElse(orderGateway);
            return TradeExecutionResult.fromOrderLifecycle(mode, gateway.submitOrder(intent, owner, mode));
        }
        return executionRouter.route(intent);
    }

    private ExecutionMode configuredMode() {
        ExecutionMode mode = tradingProperties.getMode();
        if (mode == null) {
            throw new IllegalStateException("voktrader.trading.mode must be configured explicitly");
        }
        return mode;
    }
}
