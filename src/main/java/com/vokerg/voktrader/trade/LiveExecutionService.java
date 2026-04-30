package com.vokerg.voktrader.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveExecutionService {
    private final TradeRiskCheckRepository riskCheckRepository;
    private final RiskCheckService riskCheckService;

    /**
     * Guarded placeholder. It creates a rejected audit trail for LIVE_TINY/LIVE instead of silently doing nothing.
     * Wire this to a sidecar executor only after LIVE_SHADOW has been validated.
     */
    @Transactional
    public TradeExecutionResult execute(TradeIntent intent, ExecutionMode mode) {
        String idempotencyKey = mode + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.strategyId() + ":" + intent.side();
        RiskAssessment risk = riskCheckService.assess(intent, mode, null, null, idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());

        String message = risk.passed()
                ? "real live executor is not wired yet; refusing to submit order"
                : risk.firstBlockMessage();

        log.warn("{} blocked before trade creation: strategy={} marketId={} reason={}",
                mode, intent.strategyId(), intent.marketId(), message);
        return TradeExecutionResult.rejected(mode, null, null, null, null, message);
    }
}
