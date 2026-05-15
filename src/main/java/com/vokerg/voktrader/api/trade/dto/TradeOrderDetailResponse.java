package com.vokerg.voktrader.api.trade.dto;

import java.util.List;

public record TradeOrderDetailResponse(
        TradeOrderResponse order,
        List<TradeFillResponse> fills,
        List<TradeEventResponse> events
) {
}
