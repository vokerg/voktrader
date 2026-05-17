package com.vokerg.voktrader.trade;

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
import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.ExecutorCancelOrderResponse;
import com.vokerg.voktrader.executor.ExecutorFillsResponse;
import com.vokerg.voktrader.executor.ExecutorOpenOrdersResponse;
import com.vokerg.voktrader.executor.ExecutorOrderStatusResponse;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import com.vokerg.voktrader.telemetry.TelemetryData;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveExecutionService {
    private final RiskCheckService riskCheckService;
    private final TradeRiskCheckRepository riskCheckRepository;
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final TradeEventRepository tradeEventRepository;
    private final PythonExecutorClient pythonExecutorClient;
    private final ExecutorProperties executorProperties;
    private final TradingProperties tradingProperties;
    private final PolymarketFeeCalculator feeCalculator;
    private final TradingEventLogger eventLogger;
    private final ObjectMapper objectMapper;

    @Transactional
    public TradeExecutionResult execute(TradeIntent intent, ExecutionMode mode) {
        if (intent.side() == TradeSide.SELL) {
            return executeSell(intent, mode);
        }
        return executeBuy(intent, mode);
    }

    public ExecutorCancelOrderResponse cancelRemoteOrder(String remoteOrderId) {
        return pythonExecutorClient.cancelOrder(remoteOrderId);
    }

    public ExecutorOrderStatusResponse fetchRemoteOrderStatus(String remoteOrderId) {
        return pythonExecutorClient.getOrderStatus(remoteOrderId);
    }

    public ExecutorOpenOrdersResponse fetchOpenRemoteOrders(String marketId, String tokenId) {
        return pythonExecutorClient.listOpenOrders(marketId, tokenId);
    }

    public ExecutorFillsResponse fetchRemoteFills(
            String remoteOrderId,
            String marketId,
            String tokenId,
            TradeSide side,
            BigDecimal price,
            BigDecimal shares,
            Instant since
    ) {
        return pythonExecutorClient.listFills(remoteOrderId, marketId, tokenId, side, price, shares, since);
    }

    private TradeExecutionResult executeBuy(TradeIntent intent, ExecutionMode mode) {
        if (intent.orderType().canRestOnBook() && executorProperties.isRequireImmediateFill()) {
            String message = "LIVE maker entry rejected before executor call: "
                    + "orderType=" + intent.orderType()
                    + " can rest on the book while voktrader.executor.require-immediate-fill=true";
            emitRejected(intent, mode, null, null, message, TelemetryData.data("orderType", intent.orderType()));
            return TradeExecutionResult.rejected(mode, null, null, null, null, message);
        }

        String idempotencyKey = idempotencyKey(intent, mode);
        RiskAssessment risk = riskCheckService.assess(intent, mode, null, null, idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());
        if (!risk.passed()) {
            emitRejected(intent, mode, null, null, risk.firstBlockMessage(), TelemetryData.data("side", intent.side()));
            return TradeExecutionResult.rejected(mode, null, null, null, null, risk.firstBlockMessage());
        }

        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), null, null, "TRADE_CREATED", mode + " trade created from accepted intent", null));

        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, idempotencyKey));
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ORDER_CREATED", mode + " order created", null));

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, idempotencyKey, executorProperties.isDryRun());
        order.markSubmitting(command.idempotencyKey(), toJson(command));
        order = tradeOrderRepository.save(order);

        ExecutorOrderResponse response = pythonExecutorClient.submit(command);
        order.attachExecutorResponse(response.exchangeOrderId(), response.rawResponse());

        if (!response.accepted()) {
            String message = "LIVE order rejected: " + response.safeMessage();
            order.markFailed(message, response.rawResponse());
            trade.markFailed(message);
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_ORDER_REJECTED", message, response.rawResponse()));
            emitRejected(intent, mode, trade, order, message, TelemetryData.data("executorStatus", response.status(), "executorMessage", response.safeMessage()));
            return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        if (!response.filled()) {
            if (executorProperties.isRequireImmediateFill()) {
                String message = "LIVE order was accepted but not filled immediately: " + response.safeMessage();
                order.markFailed(message, response.rawResponse());
                trade.markFailed(message);
                tradeOrderRepository.save(order);
                tradeRepository.save(trade);
                tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_ORDER_UNFILLED", message, response.rawResponse()));
                emitRejected(intent, mode, trade, order, message, TelemetryData.data("executorStatus", response.status(), "executorMessage", response.safeMessage()));
                return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
            }

            order.markSubmitted(response.exchangeOrderId(), response.rawResponse());
            trade.markEntryPending();
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_ORDER_SUBMITTED", response.safeMessage(), response.rawResponse()));
            emitExecution(
                    "LIVE_ORDER_SUBMITTED",
                    intent,
                    mode,
                    trade,
                    order,
                    "live order submitted",
                    TelemetryData.data("exchangeOrderId", response.exchangeOrderId(), "executorStatus", response.status())
            );
            return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live order submitted");
        }

        BigDecimal fillPrice = firstNonNull(response.averagePrice(), intent.expectedPrice());
        BigDecimal fillShares = firstNonNull(response.filledShares(), intent.shares());
        BigDecimal fillAmountUsd = firstNonNull(response.filledAmountUsd(), intent.amountUsd());
        BigDecimal feeUsd = resolveFeeUsd(intent, response, fillShares, fillPrice);

        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.polymarket(
                trade.getId(),
                order.getId(),
                response.exchangeOrderId(),
                intent.side(),
                fillPrice,
                fillShares,
                fillAmountUsd,
                feeUsd,
                response.rawResponse()
        ));

        order.markFilled(response.exchangeOrderId(), fillPrice, fillShares, fillAmountUsd);
        trade.markOpen(fillPrice, fillShares, fillAmountUsd, feeUsd, fill.getFilledAt());
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "LIVE_ORDER_FILLED", response.safeMessage(), response.rawResponse()));
        emitExecution(
                "LIVE_ORDER_FILLED",
                intent,
                mode,
                trade,
                order,
                response.safeMessage(),
                TelemetryData.data(
                        "fillId", fill.getId(),
                        "exchangeOrderId", response.exchangeOrderId(),
                        "fillPrice", fillPrice,
                        "fillShares", fillShares,
                        "fillAmountUsd", fillAmountUsd,
                        "feeUsd", feeUsd
                )
        );

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live order filled");
    }

    private TradeExecutionResult executeSell(TradeIntent intent, ExecutionMode mode) {
        if (intent.shares() == null || intent.shares().compareTo(BigDecimal.ZERO) <= 0) {
            emitRejected(intent, mode, null, null, "LIVE exit rejected before executor call: sell intent has no positive shares",
                    TelemetryData.data("shares", intent.shares()));
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    "LIVE exit rejected before executor call: sell intent has no positive shares");
        }
        if (intent.orderType().canRestOnBook() && executorProperties.isRequireImmediateFill()) {
            String message = "LIVE maker exit rejected before executor call: "
                    + "orderType=" + intent.orderType()
                    + " can rest on the book while voktrader.executor.require-immediate-fill=true";
            emitRejected(intent, mode, null, null, message, TelemetryData.data("orderType", intent.orderType()));
            return TradeExecutionResult.rejected(mode, null, null, null, null, message);
        }

        TradeEntity trade = findLatestTokenTrade(intent, TradeStatus.OPEN).orElse(null);
        if (trade == null) {
            emitRejected(intent, mode, null, null, "No open live trade to close for marketId=" + intent.marketId(), TelemetryData.data("side", intent.side()));
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    "No open live trade to close for marketId=" + intent.marketId());
        }

        String idempotencyKey = idempotencyKey(intent, mode, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, idempotencyKey));
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "EXIT_ORDER_CREATED", mode + " exit order created", null));

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, idempotencyKey, executorProperties.isDryRun());
        order.markSubmitting(command.idempotencyKey(), toJson(command));
        order = tradeOrderRepository.save(order);

        ExecutorOrderResponse response = pythonExecutorClient.submit(command);
        order.attachExecutorResponse(response.exchangeOrderId(), response.rawResponse());

        if (!response.accepted()) {
            String message = "LIVE exit rejected: " + response.safeMessage();
            order.markFailed(message, response.rawResponse());
            tradeOrderRepository.save(order);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_EXIT_REJECTED", message, response.rawResponse()));
            emitRejected(intent, mode, trade, order, message, TelemetryData.data("executorStatus", response.status(), "executorMessage", response.safeMessage()));
            return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        if (!response.filled()) {
            if (executorProperties.isRequireImmediateFill()) {
                String message = "LIVE exit was accepted but not filled immediately: " + response.safeMessage();
                order.markFailed(message, response.rawResponse());
                tradeOrderRepository.save(order);
                tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_EXIT_UNFILLED", message, response.rawResponse()));
                emitRejected(intent, mode, trade, order, message, TelemetryData.data("executorStatus", response.status(), "executorMessage", response.safeMessage()));
                return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
            }

            order.markSubmitted(response.exchangeOrderId(), response.rawResponse());
            tradeOrderRepository.save(order);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_EXIT_SUBMITTED", response.safeMessage(), response.rawResponse()));
            emitExecution(
                    "LIVE_EXIT_SUBMITTED",
                    intent,
                    mode,
                    trade,
                    order,
                    response.safeMessage(),
                    TelemetryData.data("exchangeOrderId", response.exchangeOrderId(), "executorStatus", response.status())
            );
            return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live exit submitted");
        }

        BigDecimal fillPrice = firstNonNull(response.averagePrice(), intent.expectedPrice());
        BigDecimal fillShares = firstNonNull(response.filledShares(), firstNonNull(intent.shares(), trade.getEntryFilledShares()));
        BigDecimal fillAmountUsd = firstNonNull(response.filledAmountUsd(), fillPrice.multiply(fillShares));
        BigDecimal feeUsd = resolveFeeUsd(intent, response, fillShares, fillPrice);

        TradeFillEntity fill = tradeFillRepository.save(TradeFillEntity.polymarket(
                trade.getId(),
                order.getId(),
                response.exchangeOrderId(),
                intent.side(),
                fillPrice,
                fillShares,
                fillAmountUsd,
                feeUsd,
                response.rawResponse()
        ));

        order.markFilled(response.exchangeOrderId(), fillPrice, fillShares, fillAmountUsd);
        trade.markClosed(fillPrice, fillShares, fillAmountUsd, feeUsd, fill.getFilledAt());
        tradeOrderRepository.save(order);
        tradeRepository.save(trade);
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), fill.getId(), "LIVE_EXIT_FILLED", response.safeMessage(), response.rawResponse()));
        emitExecution(
                "LIVE_EXIT_FILLED",
                intent,
                mode,
                trade,
                order,
                response.safeMessage(),
                TelemetryData.data(
                        "fillId", fill.getId(),
                        "exchangeOrderId", response.exchangeOrderId(),
                        "fillPrice", fillPrice,
                        "fillShares", fillShares,
                        "fillAmountUsd", fillAmountUsd,
                        "feeUsd", feeUsd,
                        "finalPnlUsd", trade.getFinalPnlUsd()
                )
        );

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live exit filled");
    }

    private void emitRejected(
            TradeIntent intent,
            ExecutionMode mode,
            TradeEntity trade,
            TradeOrderEntity order,
            String reason,
            java.util.Map<String, Object> data
    ) {
        emitExecution("TRADE_REJECTED", intent, mode, trade, order, reason, data);
    }

    private void emitExecution(
            String type,
            TradeIntent intent,
            ExecutionMode mode,
            TradeEntity trade,
            TradeOrderEntity order,
            String reason,
            java.util.Map<String, Object> data
    ) {
        data.put("mode", mode);
        data.put("side", intent.side());
        data.put("tradeId", trade == null ? null : trade.getId());
        data.put("orderId", order == null ? null : order.getId());
        eventLogger.execution(
                type,
                "EXECUTION",
                intent.strategyId(),
                intent.ruleId(),
                intent.botId(),
                intent.marketId(),
                intent.tokenId(),
                intent.outcome(),
                reason,
                data,
                true
        );
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode) {
        Instant decisionAt = intent.decisionAt() != null ? intent.decisionAt() : Instant.now();
        return mode + ":" + botScope(intent.botId()) + ":" + intent.strategyId() + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.side() + ":" + decisionAt.toEpochMilli();
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode, Long tradeId) {
        Instant decisionAt = intent.decisionAt() != null ? intent.decisionAt() : Instant.now();
        return mode + ":" + botScope(intent.botId()) + ":" + intent.strategyId() + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.side() + ":" + tradeId + ":" + decisionAt.toEpochMilli();
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

    private String botScope(Long botId) {
        return botId == null ? "default" : botId.toString();
    }

    private BigDecimal resolveFeeUsd(TradeIntent intent, ExecutorOrderResponse response, BigDecimal shares, BigDecimal price) {
        if (response.feeUsd() != null) {
            return response.feeUsd();
        }
        if (!tradingProperties.isEstimateLiveFeesWhenMissing()) {
            return BigDecimal.ZERO;
        }
        BigDecimal feeRate = intent.expectedLiquidityRole() == com.vokerg.voktrader.economy.LiquidityRole.MAKER
                ? tradingProperties.getMakerFeeRate()
                : tradingProperties.getTakerFeeRate();
        BigDecimal feeUsd = feeCalculator.estimateFeeUsd(shares, price, feeRate);
        log.info(
                "LIVE fee missing from executor; estimated {} fee feeUsd={} shares={} price={} feeRate={}",
                intent.expectedLiquidityRole(),
                feeUsd,
                shares,
                price,
                feeRate
        );
        eventLogger.execution(
                "LIVE_FEE_ESTIMATED",
                "EXECUTION",
                null,
                null,
                null,
                null,
                null,
                null,
                "executor omitted fee",
                TelemetryData.data("feeUsd", feeUsd, "shares", shares, "price", price, "feeRate", tradingProperties.getTakerFeeRate()),
                true
        );
        return feeUsd;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            log.error("Failed to serialize object to JSON: {}", obj, e);
            return "{\"error\": \"serialization failed\"}";
        }
    }
}
