package com.vokerg.voktrader.trade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveShadowExecutionService {
    private static final int SHARE_SCALE = 8;

    private final TradingProperties properties;
    private final RiskCheckService riskCheckService;
    private final PaperFeeCalculator feeCalculator;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeRiskCheckRepository riskCheckRepository;
    private final TradeEventRepository eventRepository;

    @Transactional
    public TradeExecutionResult execute(TradeIntent intent) {
        ExecutionMode mode = ExecutionMode.LIVE_SHADOW;
        String idempotencyKey = idempotencyKey(intent, mode);
        RiskAssessment risk = riskCheckService.assess(intent, mode, null, null, idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());
        if (!risk.passed()) {
            return TradeExecutionResult.rejected(mode, null, null, null, null, risk.firstBlockMessage());
        }

        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        eventRepository.save(TradeEventEntity.of(trade.getId(), null, null, "TRADE_CREATED", "live-shadow trade created from accepted intent", null));

        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, idempotencyKey));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ORDER_CREATED", "live-shadow order recorded; no money moved", null));

        BigDecimal expectedShares = null;
        BigDecimal expectedFee = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        if (intent.side() == TradeSide.BUY && intent.expectedPrice() != null && intent.expectedPrice().compareTo(BigDecimal.ZERO) > 0) {
            expectedShares = intent.amountUsd().divide(intent.expectedPrice(), SHARE_SCALE, RoundingMode.HALF_UP);
            expectedFee = feeCalculator.estimate(expectedShares, intent.expectedPrice(), properties.getPaperFeeRate());
        }

        order.markShadowRecorded();
        trade.markShadowRecorded();
        tradeRepository.save(trade);
        tradeOrderRepository.save(order);
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "SHADOW_RECORDED",
                "live-shadow order passed risk checks; not submitted to Polymarket",
                "{\"expectedShares\":\"" + (expectedShares == null ? "" : expectedShares) + "\"}"));

        log.info(
                "LIVE_SHADOW RECORDED: tradeId={} orderId={} strategy={} marketId={} outcome={} side={} limitPrice={} amountUsd={} expectedShares={} expectedFee={} spread={} priceAgeMs={} reason={}",
                trade.getId(), order.getId(), trade.getStrategyId(), trade.getMarketId(), trade.getOutcome(), intent.side(),
                intent.expectedPrice(), intent.amountUsd(), expectedShares, expectedFee, intent.observedSpread(), intent.priceAgeMs(), intent.reason());

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live-shadow order recorded");
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode) {
        return mode + ":" + botScope(intent.botId()) + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.strategyId() + ":" + intent.side();
    }

    private String botScope(Long botId) {
        return botId == null ? "default" : botId.toString();
    }
}
