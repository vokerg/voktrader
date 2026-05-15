package com.vokerg.voktrader.api.trade;

import com.vokerg.voktrader.api.trade.dto.TradeOrderDetailResponse;
import com.vokerg.voktrader.api.trade.dto.TradeOrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderApiController {
    private final OrderQueryService orderQueryService;

    public OrderApiController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @GetMapping
    public List<TradeOrderResponse> list(
            @RequestParam(required = false) Long botId,
            @RequestParam(required = false) String strategyId,
            @RequestParam(required = false) String marketId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Integer limit
    ) {
        return orderQueryService.list(botId, strategyId, marketId, status, side, orderType, phase, mode, limit);
    }

    @GetMapping("/{id}")
    public TradeOrderDetailResponse get(@PathVariable Long id) {
        return orderQueryService.get(id);
    }
}
