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
        if (intent.side() == TradeSide.SELL) {
            return executeSell(intent);
        }

        if (intent.side() != TradeSide.BUY) {
            return TradeExecutionResult.rejected(ExecutionMode.PAPER, null, null, null, null, "paper execution only supports BUY and SELL intents");
        }

        return executeBuy(intent);
    }

    private TradeExecutionResult executeBuy(TradeIntent intent) {
        ExecutionMode mode = ExecutionMode.PAPER;
        String idempotencyKey = idempotencyKey(intent, mode);
        RiskAssessment risk = riskCheckService.assess(intent, mode, null, null, idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());
        if (!risk.passed()) {
            return TradeExecutionResult.rejected(mode, null, null, null, null, risk.firstBlockMessage());
        }

        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        eventRepository.save(TradeEventEntity.of(trade.getId(), null, null, "TRADE_CREATED", "paper trade created from accepted intent", null));
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.PAPER_SIM, idempotencyKey));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ORDER_CREATED", "paper simulated order created", null));

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

        order.markFilled(null, entryPrice, shares, intent.amountUsd());
        trade.markOpen(entryPrice, shares, intent.amountUsd(), fee, order.getCompletedAt());

        tradeRepository.save(trade);
        tradeOrderRepository.save(order);
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.synthetic(
                trade.getId(), order.getId(), intent.side(), entryPrice, shares, intent.amountUsd()));

        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "ORDER_FILLED", "paper order filled at observed ask", null));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "POSITION_OPENED", "paper position opened", null));

        log.info(
                "PAPER TRADE OPENED: tradeId={} orderId={} strategy={} marketId={} outcome={} tokenId={} price={} amountUsd={} shares={} fee={} spread={} priceAgeMs={} reason={}",
                trade.getId(), order.getId(), trade.getStrategyId(), trade.getMarketId(), trade.getOutcome(), trade.getTokenId(),
                entryPrice, intent.amountUsd(), shares, fee, intent.observedSpread(), intent.priceAgeMs(), intent.reason());

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "paper order filled");
    }

    private TradeExecutionResult executeSell(TradeIntent intent) {
        ExecutionMode mode = ExecutionMode.PAPER;
        TradeEntity trade = findLatestTokenTrade(intent, TradeStatus.OPEN).orElse(null);
        if (trade == null) {
            boolean hasClosedTrade = findLatestTokenTrade(intent, TradeStatus.CLOSED).isPresent();
            return TradeExecutionResult.rejected(mode, null, null, hasClosedTrade ? TradeStatus.CLOSED : null, null,
                    hasClosedTrade ? "trade already closed" : "no open trade to close");
        }

        BigDecimal exitPrice = intent.observedBid();
        if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return TradeExecutionResult.rejected(mode, trade.getId(), null, trade.getStatus(), null,
                    "paper exit price is missing or non-positive");
        }

        BigDecimal shares = trade.getEntryFilledShares();
        if (shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            return TradeExecutionResult.rejected(mode, trade.getId(), null, trade.getStatus(), null,
                    "open trade has no shares to close");
        }

        BigDecimal exitAmountUsd = shares.multiply(exitPrice).setScale(SHARE_SCALE, RoundingMode.HALF_UP);
        String idempotencyKey = idempotencyKey(intent, mode, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.PAPER_SIM, idempotencyKey));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "EXIT_ORDER_CREATED", "paper simulated exit order created", null));

        BigDecimal fee = feeCalculator.estimate(shares, exitPrice, properties.getPaperFeeRate());
        order.markFilled(null, exitPrice, shares, exitAmountUsd);
        trade.markClosed(exitPrice, shares, exitAmountUsd, fee, order.getCompletedAt());

        tradeRepository.save(trade);
        tradeOrderRepository.save(order);
        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.synthetic(
                trade.getId(), order.getId(), intent.side(), exitPrice, shares, exitAmountUsd));

        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "EXIT_FILLED", intent.reason(), null));
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "CLOSED", intent.reason(), null));

        log.info(
                "PAPER TRADE CLOSED: tradeId={} orderId={} strategy={} marketId={} outcome={} tokenId={} entryPrice={} exitPrice={} shares={} entryFee={} exitFee={} pnlUsd={} reason={}",
                trade.getId(), order.getId(), trade.getStrategyId(), trade.getMarketId(), trade.getOutcome(), trade.getTokenId(),
                trade.getEntryAvgPrice(), exitPrice, shares, trade.getEntryFeeUsd(), fee, trade.getRealizedPnlUsd(), intent.reason());

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "paper exit filled");
    }

    private java.util.Optional<TradeEntity> findLatestTokenTrade(TradeIntent intent, TradeStatus status) { if (intent.botId() != null) { return tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(intent.botId(), intent.strategyId(), intent.marketId(), intent.tokenId(), status); } return tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(intent.strategyId(), intent.marketId(), intent.tokenId(), status); } private String idempotencyKey(TradeIntent intent, ExecutionMode mode, Long tradeId) { return mode + ":" + (intent.botId() == null ? "default" : intent.botId()) + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.strategyId() + ":" + intent.side() + ":" + tradeId; }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode) { return mode + ":" + (intent.botId() == null ? "default" : intent.botId()) + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.strategyId() + ":" + intent.side(); }
}
