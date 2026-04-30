package com.vokerg.voktrader.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveExecutionService {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeRiskCheckRepository riskCheckRepository;
    private final TradeEventRepository eventRepository;
    private final RiskCheckService riskCheckService;

    /**
     * Guarded placeholder. It creates a rejected audit trail for LIVE_TINY/LIVE instead of silently doing nothing.
     * Wire this to a sidecar executor only after LIVE_SHADOW has been validated.
     */
    @Transactional
    public TradeExecutionResult execute(TradeIntent intent, ExecutionMode mode) {
        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        eventRepository.save(TradeEventEntity.of(trade.getId(), null, null, "TRADE_CREATED", mode + " trade created from intent", null));

        String idempotencyKey = mode + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.strategyId() + ":" + intent.side() + ":" + trade.getId();
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, idempotencyKey));

        RiskAssessment risk = riskCheckService.assess(intent, mode, trade.getId(), order.getId(), idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());

        String message = risk.passed()
                ? "real live executor is not wired yet; refusing to submit order"
                : risk.firstBlockMessage();
        trade.markRiskRejected();
        order.markRiskRejected(message);
        tradeRepository.save(trade);
        tradeOrderRepository.save(order);
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_NOT_SUBMITTED", message, null));

        log.warn("{} blocked: tradeId={} orderId={} strategy={} marketId={} reason={}", mode, trade.getId(), order.getId(), trade.getStrategyId(), trade.getMarketId(), message);
        return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
    }
}
