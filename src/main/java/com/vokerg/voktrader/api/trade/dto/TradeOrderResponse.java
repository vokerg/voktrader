package com.vokerg.voktrader.api.trade.dto;

import com.vokerg.voktrader.economy.LiquidityRole;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeVenue;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeOrderResponse(
        Long id,
        Long botId,
        Long tradeId,
        String localOrderId,
        String clientOrderId,
        String idempotencyKey,
        String remoteOrderId,
        String exchangeOrderId,
        String strategyId,
        String configHash,
        String accountId,
        String ruleId,
        String marketId,
        String tokenId,
        String outcome,
        TradeSide side,
        TradeOrderPhase phase,
        ExecutionMode mode,
        TradeVenue venue,
        TradeOrderType orderType,
        Boolean postOnly,
        TradeOrderStatus status,
        BigDecimal requestedPrice,
        BigDecimal requestedShares,
        BigDecimal requestedAmountUsd,
        BigDecimal filledPrice,
        BigDecimal filledShares,
        BigDecimal filledAmountUsd,
        BigDecimal remainingShares,
        BigDecimal avgFillPrice,
        BigDecimal realizedFeeUsd,
        Boolean feeKnown,
        LiquidityRole fillRole,
        String rejectReason,
        String rejectionReason,
        String errorMessage,
        String failureReason,
        String cancelReason,
        Instant submittedAt,
        Instant acknowledgedAt,
        Instant completedAt,
        Instant lastReconciledAt,
        Instant expiresAt,
        Long latencyMs,
        String rawRequest,
        String rawResponse,
        Instant createdAt,
        Instant updatedAt
) {
    public static TradeOrderResponse from(TradeOrderEntity entity) {
        return new TradeOrderResponse(
                entity.getId(),
                entity.getBotId(),
                entity.getTradeId(),
                entity.getLocalOrderId(),
                entity.getClientOrderId(),
                entity.getIdempotencyKey(),
                entity.getRemoteOrderId(),
                entity.getExchangeOrderId(),
                entity.getStrategyId(),
                entity.getConfigHash(),
                entity.getAccountId(),
                entity.getRuleId(),
                entity.getMarketId(),
                entity.getTokenId(),
                entity.getOutcome(),
                entity.getSide(),
                entity.getPhase(),
                entity.getMode(),
                entity.getVenue(),
                entity.getOrderType(),
                entity.getPostOnly(),
                entity.getStatus(),
                entity.getRequestedPrice(),
                entity.getRequestedShares(),
                entity.getRequestedAmountUsd(),
                entity.getFilledPrice(),
                entity.getFilledShares(),
                entity.getFilledAmountUsd(),
                entity.getRemainingShares(),
                entity.getAvgFillPrice(),
                entity.getRealizedFeeUsd(),
                entity.getFeeKnown(),
                entity.getFillRole(),
                entity.getRejectReason(),
                entity.getRejectionReason(),
                entity.getErrorMessage(),
                entity.getFailureReason(),
                entity.getCancelReason(),
                entity.getSubmittedAt(),
                entity.getAcknowledgedAt(),
                entity.getCompletedAt(),
                entity.getLastReconciledAt(),
                entity.getExpiresAt(),
                entity.getLatencyMs(),
                entity.getRawRequest(),
                entity.getRawResponse(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
