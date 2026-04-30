package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorOrderCommand;
import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.executor.PythonExecutorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public TradeExecutionResult execute(TradeIntent intent, ExecutionMode mode) {
        if (intent.side() == TradeSide.SELL) {
            return executeSell(intent, mode);
        }
        return executeBuy(intent, mode);
    }

    private TradeExecutionResult executeBuy(TradeIntent intent, ExecutionMode mode) {
        String idempotencyKey = idempotencyKey(intent, mode);
        RiskAssessment risk = riskCheckService.assess(intent, mode, null, null, idempotencyKey);
        riskCheckRepository.saveAll(risk.checks());
        if (!risk.passed()) {
            return TradeExecutionResult.rejected(mode, null, null, null, null, risk.firstBlockMessage());
        }

        TradeEntity trade = tradeRepository.save(TradeEntity.fromIntent(intent, mode));
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), null, null, "TRADE_CREATED", mode + " trade created from accepted intent", null));

        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, idempotencyKey));
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "ORDER_CREATED", mode + " order created", null));

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, idempotencyKey, executorProperties.isDryRun());
        order.markSubmitting(command.idempotencyKey(), command.toString());
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
                return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
            }

            order.markSubmitted(response.exchangeOrderId(), response.rawResponse());
            trade.markEntryPending();
            tradeOrderRepository.save(order);
            tradeRepository.save(trade);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_ORDER_SUBMITTED", response.safeMessage(), response.rawResponse()));
            return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live order submitted");
        }

        BigDecimal fillPrice = firstNonNull(response.averagePrice(), intent.expectedPrice());
        BigDecimal fillShares = firstNonNull(response.filledShares(), intent.shares());
        BigDecimal fillAmountUsd = firstNonNull(response.filledAmountUsd(), intent.amountUsd());
        BigDecimal feeUsd = firstNonNull(response.feeUsd(), BigDecimal.ZERO);

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

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live order filled");
    }

    private TradeExecutionResult executeSell(TradeIntent intent, ExecutionMode mode) {
        TradeEntity trade = tradeRepository
                .findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                        intent.strategyId(),
                        intent.marketId(),
                        intent.tokenId(),
                        TradeStatus.OPEN
                )
                .orElse(null);
        if (trade == null) {
            return TradeExecutionResult.rejected(mode, null, null, null, null,
                    "No open live trade to close for marketId=" + intent.marketId());
        }

        String idempotencyKey = idempotencyKey(intent, mode, trade.getId());
        TradeOrderEntity order = tradeOrderRepository.save(TradeOrderEntity.fromIntent(
                trade.getId(), intent, mode, TradeVenue.POLYMARKET, idempotencyKey));
        tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "EXIT_ORDER_CREATED", mode + " exit order created", null));

        ExecutorOrderCommand command = ExecutorOrderCommand.fromIntent(intent, idempotencyKey, executorProperties.isDryRun());
        order.markSubmitting(command.idempotencyKey(), command.toString());
        order = tradeOrderRepository.save(order);

        ExecutorOrderResponse response = pythonExecutorClient.submit(command);
        order.attachExecutorResponse(response.exchangeOrderId(), response.rawResponse());

        if (!response.accepted()) {
            String message = "LIVE exit rejected: " + response.safeMessage();
            order.markFailed(message, response.rawResponse());
            tradeOrderRepository.save(order);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_EXIT_REJECTED", message, response.rawResponse()));
            return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
        }

        if (!response.filled()) {
            if (executorProperties.isRequireImmediateFill()) {
                String message = "LIVE exit was accepted but not filled immediately: " + response.safeMessage();
                order.markFailed(message, response.rawResponse());
                tradeOrderRepository.save(order);
                tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_EXIT_UNFILLED", message, response.rawResponse()));
                return TradeExecutionResult.rejected(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), message);
            }

            order.markSubmitted(response.exchangeOrderId(), response.rawResponse());
            tradeOrderRepository.save(order);
            tradeEventRepository.save(TradeEventEntity.of(trade.getId(), order.getId(), null, "LIVE_EXIT_SUBMITTED", response.safeMessage(), response.rawResponse()));
            return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live exit submitted");
        }

        BigDecimal fillPrice = firstNonNull(response.averagePrice(), intent.expectedPrice());
        BigDecimal fillShares = firstNonNull(response.filledShares(), firstNonNull(intent.shares(), trade.getEntryFilledShares()));
        BigDecimal fillAmountUsd = firstNonNull(response.filledAmountUsd(), fillPrice.multiply(fillShares));
        BigDecimal feeUsd = firstNonNull(response.feeUsd(), BigDecimal.ZERO);

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

        return TradeExecutionResult.accepted(mode, trade.getId(), order.getId(), trade.getStatus(), order.getStatus(), "live exit filled");
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode) {
        Instant decisionAt = intent.decisionAt() != null ? intent.decisionAt() : Instant.now();
        return mode + ":" + intent.strategyId() + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.side() + ":" + decisionAt.toEpochMilli();
    }

    private String idempotencyKey(TradeIntent intent, ExecutionMode mode, Long tradeId) {
        return mode + ":" + intent.strategyId() + ":" + intent.marketId() + ":" + intent.tokenId() + ":" + intent.side() + ":" + tradeId;
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }
}
