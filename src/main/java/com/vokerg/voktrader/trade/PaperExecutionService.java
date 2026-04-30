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
public class PaperExecutionService {
    private static final int SHARE_SCALE = 8;

    private final TradingProperties properties;
    private final RiskCheckService riskCheckService;
    private final PaperFeeCalculator feeCalculator;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final TradeRiskCheckRepository riskCheckRepository;
    private final TradeEventRepository eventRepository;

    @Transactional
    public TradeExecutionResult execute(TradeIntent intent) {
        ExecutionMode mode = ExecutionMode.PAPER;
        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        eventRepository.save(TradeEventEntity.of(trade.getId(), null, null, "TRADE_CREATED", "paper trade created from intent", null));

        String idempotencyKey = idempotencyKey(intent, mode, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.PAPER_SIM, idempotencyKey));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ORDER_CREATED", "paper simulated order created", null));

        RiskAssessment risk = riskCheckService.assess(intent, mode, trade.getId(), order.getId(), idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());
        if (!risk.passed()) {
            trade.markRiskRejected();
            order.markRiskRejected(risk.firstBlockMessage());
            tradeRepository.save(trade);
            tradeOrderRepository.save(order);
            eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "RISK_BLOCKED", risk.firstBlockMessage(), null));
            return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), risk.firstBlockMessage());
        }

        if (intent.side() != TradeSide.BUY) {
            String message = "paper v1 only supports new BUY entry intents; SELL exits should be modeled in the next iteration";
            trade.markFailed(message);
            order.markFailed(message);
            tradeRepository.save(trade);
            tradeOrderRepository.save(order);
            eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ERROR", message, null));
            return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        BigDecimal entryPrice = intent.expectedPrice();
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            String message = "paper entry price is missing or non-positive";
            trade.markFailed(message);
            order.markFailed(message);
            tradeRepository.save(trade);
            tradeOrderRepository.save(order);
            eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ERROR", message, null));
            return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        BigDecimal shares = intent.amountUsd().divide(entryPrice, SHARE_SCALE, RoundingMode.HALF_UP);
        BigDecimal fee = feeCalculator.estimate(shares, entryPrice, properties.getPaperFeeRate());

        order.markFilled(shares, intent.amountUsd(), entryPrice, fee);
        trade.markOpen(entryPrice, shares, intent.amountUsd(), fee, order.getCompletedAt());

        tradeRepository.save(trade);
        tradeOrderRepository.save(order);
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.synthetic(
                trade.getId(), order.getId(), intent.side(), entryPrice, shares, intent.amountUsd(), fee));

        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "ORDER_FILLED", "paper order filled at observed ask", null));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "POSITION_OPENED", "paper position opened", null));

        log.info(
                "PAPER TRADE OPENED: tradeId={} orderId={} strategy={} marketId={} outcome={} tokenId={} price={} amountUsd={} shares={} fee={} spread={} priceAgeMs={} reason={}",
                trade.getId(), order.getId(), trade.getStrategyId(), trade.getMarketId(), trade.getOutcome(), trade.getTokenId(),
                entryPrice, intent.amountUsd(), shares, fee, intent.observedSpread(), intent.priceAgeMs(), intent.reason());

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "paper order filled");
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode, Long tradeId) {
        return mode + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.strategyId() + ":" + intent.side() + ":" + tradeId;
    }
}
