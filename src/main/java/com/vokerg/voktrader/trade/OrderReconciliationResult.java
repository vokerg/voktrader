package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderReconciliationResult(
        Long orderId,
        Long tradeId,
        String localOrderId,
        String remoteOrderId,
        TradeOrderStatus previousStatus,
        TradeOrderStatus resolvedStatus,
        boolean changed,
        boolean remoteStatusSuccess,
        boolean remoteFillsSuccess,
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
    public static OrderReconciliationResult from(
            TradeOrderEntity order,
            TradeOrderStatus previousStatus,
            boolean remoteStatusSuccess,
            boolean remoteFillsSuccess,
            String remoteStatus,
            int remoteFillsCount,
            int newFillsCount,
            List<String> warnings
    ) {
        return new OrderReconciliationResult(
                order.getId(),
                order.getTradeId(),
                order.getLocalOrderId(),
                order.getRemoteOrderId(),
                previousStatus,
                order.getStatus(),
                previousStatus != order.getStatus(),
                remoteStatusSuccess,
                remoteFillsSuccess,
                remoteStatus,
                remoteFillsCount,
                newFillsCount,
                order.getFilledShares(),
                order.getRemainingShares(),
                order.getAvgFillPrice(),
                order.getRealizedFeeUsd(),
                order.getFeeKnown(),
                warnings == null ? List.of() : List.copyOf(warnings),
                order.getLastReconciledAt()
        );
    }
}
