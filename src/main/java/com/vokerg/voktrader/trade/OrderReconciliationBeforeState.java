package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;

import java.math.BigDecimal;

public record OrderReconciliationBeforeState(
        TradeOrderStatus status,
        BigDecimal filledShares,
        BigDecimal remainingShares,
        BigDecimal avgFillPrice,
        BigDecimal feeUsd,
        Boolean feeKnown
) {
    public static OrderReconciliationBeforeState from(TradeOrderEntity order) {
        return new OrderReconciliationBeforeState(
                order.getStatus(),
                order.getFilledShares(),
                order.getRemainingShares(),
                order.getAvgFillPrice(),
                order.getRealizedFeeUsd(),
                order.getFeeKnown()
        );
    }
}
