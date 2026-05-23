package com.vokerg.voktrader.trade.paper;

import com.vokerg.voktrader.trade.RiskAssessment;
import com.vokerg.voktrader.trade.RiskCheckService;
import com.vokerg.voktrader.trade.TradeExecutionResult;
import com.vokerg.voktrader.trade.TradeExecutionSafetyService;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.TradePositionSupport;
import com.vokerg.voktrader.trade.TradingProperties;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

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
    private final TradingEventLogger eventLogger;
    private final TradeExecutionSafetyService safetyService;

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
            eventLogger.execution(
                    "TRADE_REJECTED",
                    "EXECUTION",
                    intent.strategyId(),
                    intent.ruleId(),
                    intent.botId(),
                    intent.marketId(),
                    intent.tokenId(),
                    intent.outcome(),
                    risk.firstBlockMessage(),
                    TelemetryData.data("mode", mode, "side", intent.side()),
                    true
            );
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
            eventLogger.execution(
                    "TRADE_REJECTED",
                    "EXECUTION",
                    intent.strategyId(),
                    intent.ruleId(),
                    intent.botId(),
                    intent.marketId(),
                    intent.tokenId(),
                    intent.outcome(),
                    message,
                    TelemetryData.data("mode", mode, "tradeId", trade.getId(), "orderId", order.getId(), "side", intent.side()),
                    true
            );
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
        eventLogger.execution(
                "PAPER_TRADE_OPENED",
                "EXECUTION",
                trade.getStrategyId(),
                trade.getRuleId(),
                trade.getBotId(),
                trade.getMarketId(),
                trade.getTokenId(),
                trade.getOutcome(),
                intent.reason(),
                TelemetryData.data(
                        "mode", mode,
                        "tradeId", trade.getId(),
                        "orderId", order.getId(),
                        "fillId", fill.getId(),
                        "price", entryPrice,
                        "amountUsd", intent.amountUsd(),
                        "shares", shares,
                        "feeUsd", fee,
                        "spread", intent.observedSpread(),
                        "priceAgeMs", intent.priceAgeMs()
                ),
                true
        );

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "paper order filled");
    }

    private TradeExecutionResult executeSell(TradeIntent intent) {
        ExecutionMode mode = ExecutionMode.PAPER;
        TradeEntity trade = findLatestActivePositionTrade(intent).orElse(null);
        if (trade == null) {
            boolean hasClosedTrade = findLatestTokenTrade(intent, TradeStatus.CLOSED).isPresent();
            String message = hasClosedTrade ? "trade already closed" : "no open or partially open trade to close";
            eventLogger.execution(
                    "TRADE_REJECTED",
                    "EXECUTION",
                    intent.strategyId(),
                    intent.ruleId(),
                    intent.botId(),
                    intent.marketId(),
                    intent.tokenId(),
                    intent.outcome(),
                    message,
                    TelemetryData.data("mode", mode, "side", intent.side(), "hasClosedTrade", hasClosedTrade),
                    true
            );
            return TradeExecutionResult.rejected(mode, null, null, hasClosedTrade ? TradeStatus.CLOSED : null, null,
                    message);
        }
        Optional<TradeExecutionResult> blocked = safetyService.rejectPaperExitIfLiveBacked(
                trade,
                intent,
                mode,
                "PaperExecutionService.executeSell"
        );
        if (blocked.isPresent()) {
            return blocked.get();
        }

        TradePositionSupport.ExitPlan exitPlan = TradePositionSupport.planExit(trade, intent.shares());
        if (!exitPlan.hasRequestedShares()) {
            eventLogger.execution(
                    "TRADE_REJECTED",
                    "EXECUTION",
                    intent.strategyId(),
                    intent.ruleId(),
                    intent.botId(),
                    intent.marketId(),
                    intent.tokenId(),
                    intent.outcome(),
                    "open trade has no held shares to close",
                    TelemetryData.data("mode", mode, "tradeId", trade.getId(), "side", intent.side(), "heldShares", exitPlan.heldShares()),
                    true
            );
            return TradeExecutionResult.rejected(mode, trade.getId(), null, trade.getStatus(), null,
                    "open trade has no held shares to close");
        }
        TradeIntent sellIntent = TradePositionSupport.withSellShares(intent, exitPlan.requestedShares());

        BigDecimal exitPrice = intent.observedBid();
        if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            eventLogger.execution(
                    "TRADE_REJECTED",
                    "EXECUTION",
                    intent.strategyId(),
                    intent.ruleId(),
                    intent.botId(),
                    intent.marketId(),
                    intent.tokenId(),
                    intent.outcome(),
                    "paper exit price is missing or non-positive",
                    TelemetryData.data("mode", mode, "tradeId", trade.getId(), "side", intent.side(), "exitPrice", exitPrice),
                    true
            );
            return TradeExecutionResult.rejected(mode, trade.getId(), null, trade.getStatus(), null,
                    "paper exit price is missing or non-positive");
        }

        BigDecimal shares = sellIntent.shares();
        BigDecimal exitAmountUsd = shares.multiply(exitPrice).setScale(SHARE_SCALE, RoundingMode.HALF_UP);
        String idempotencyKey = idempotencyKey(sellIntent, mode, trade.getId());
        TradeOrderEntity order = TradeOrderEntity.fromIntent(
                trade.getId(), sellIntent, mode, TradeVenue.PAPER_SIM, idempotencyKey);
        safetyService.assertNoPaperExitOrderForLiveBackedTrade(order, "PaperExecutionService.beforeExitOrderSave");
        order = tradeOrderRepository.save(order);
        eventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "EXIT_ORDER_CREATED", "paper simulated exit order created", null));

        BigDecimal fee = feeCalculator.estimate(shares, exitPrice, properties.getPaperFeeRate());
        order.markFilled(null, exitPrice, shares, exitAmountUsd);
        safetyService.assertNoPaperExitOrderForLiveBackedTrade(order, "PaperExecutionService.beforeFilledExitOrderSave");
        safetyService.assertPaperMayCloseTrade(trade, intent, "PaperExecutionService.beforeMarkClosed");
        BigDecimal cumulativeExitShares = TradePositionSupport.cumulativeExitShares(trade, shares);
        BigDecimal cumulativeExitAmountUsd = TradePositionSupport.cumulativeExitAmountUsd(trade, exitAmountUsd);
        BigDecimal cumulativeExitFeeUsd = TradePositionSupport.cumulativeExitFeeUsd(trade, fee);
        if (TradePositionSupport.closesPosition(trade, shares)) {
            trade.markClosed(exitPrice, cumulativeExitShares, cumulativeExitAmountUsd, cumulativeExitFeeUsd, order.getCompletedAt());
        } else {
            trade.markPartiallyClosed(exitPrice, cumulativeExitShares, cumulativeExitAmountUsd, cumulativeExitFeeUsd, order.getCompletedAt());
        }

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
        eventLogger.execution(
                "PAPER_TRADE_CLOSED",
                "EXECUTION",
                trade.getStrategyId(),
                trade.getRuleId(),
                trade.getBotId(),
                trade.getMarketId(),
                trade.getTokenId(),
                trade.getOutcome(),
                intent.reason(),
                TelemetryData.data(
                        "mode", mode,
                        "tradeId", trade.getId(),
                        "orderId", order.getId(),
                        "fillId", fill.getId(),
                        "entryPrice", trade.getEntryAvgPrice(),
                        "exitPrice", exitPrice,
                        "shares", shares,
                        "entryFeeUsd", trade.getEntryFeeUsd(),
                        "exitFeeUsd", fee,
                        "realizedPnlUsd", trade.getRealizedPnlUsd()
                ),
                true
        );

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "paper exit filled");
    }

    private java.util.Optional<TradeEntity> findLatestTokenTrade(TradeIntent intent, TradeStatus status) {
        if (intent.botId() != null) {
            return tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                    intent.botId(),
                    intent.strategyId(),
                    intent.marketId(),
                    intent.tokenId(),
                    status
            );
        }
        return tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                intent.strategyId(),
                intent.marketId(),
                intent.tokenId(),
                status
        );
    }

    private Optional<TradeEntity> findLatestActivePositionTrade(TradeIntent intent) {
        Optional<TradeEntity> trade = intent.botId() != null
                ? tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                intent.botId(),
                intent.strategyId(),
                intent.marketId(),
                intent.tokenId(),
                TradePositionSupport.EXITABLE_STATUSES
        )
                : tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusInOrderByCreatedAtDesc(
                intent.strategyId(),
                intent.marketId(),
                intent.tokenId(),
                TradePositionSupport.EXITABLE_STATUSES
        );
        return trade;
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode, Long tradeId) {
        return mode + ":" + (intent.botId() == null ? "default" : intent.botId())
                + ":" + intent.marketId()
                + ":" + intent.tokenId()
                + ":" + intent.strategyId()
                + ":" + intent.side()
                + ":" + tradeId;
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode) {
        return mode + ":" + (intent.botId() == null ? "default" : intent.botId())
                + ":" + intent.marketId()
                + ":" + intent.tokenId()
                + ":" + intent.strategyId()
                + ":" + intent.side();
    }
}
