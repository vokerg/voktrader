package com.vokerg.voktrader.api.trade.dto;

import com.vokerg.voktrader.trade.OrderReconciliationResult;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderReconciliationResponse(
        Long orderId,
        Long tradeId,
        String localOrderId,
        String remoteOrderId,
        TradeOrderStatus previousStatus,
        TradeOrderStatus resolvedStatus,
        boolean changed,
        boolean remoteStatusSuccess,
        String remoteStatus,
        int remoteFillsCount,
        int newFillsCount,
        BigDecimal filledShares,
        BigDecimal remainingShares,
        BigDecimal avgFillPrice,
        BigDecimal feeUsd,
        Boolean feeKnown,
        List<String> warnings,
        Instant reconciledAt
) {
    public static OrderReconciliationResponse from(OrderReconciliationResult result) {
        return new OrderReconciliationResponse(
                result.orderId(),
                result.tradeId(),
                result.localOrderId(),
                result.remoteOrderId(),
                result.previousStatus(),
                result.resolvedStatus(),
                result.changed(),
                result.remoteStatusSuccess(),
                result.remoteStatus(),
                result.remoteFillsCount(),
                result.newFillsCount(),
                result.filledShares(),
                result.remainingShares(),
                result.avgFillPrice(),
                result.feeUsd(),
                result.feeKnown(),
                result.warnings(),
                result.reconciledAt()
        );
    }
}
