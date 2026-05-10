package com.vokerg.voktrader.api.trade;

import com.vokerg.voktrader.api.trade.dto.TradeDetailResponse;
import com.vokerg.voktrader.api.trade.dto.TradeSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trades")
public class TradeApiController {
    private final TradeQueryService tradeQueryService;

    public TradeApiController(TradeQueryService tradeQueryService) {
        this.tradeQueryService = tradeQueryService;
    }

    @GetMapping
    public List<TradeSummaryResponse> list(
            @RequestParam(required = false) Long botId,
            @RequestParam(required = false) String strategyId,
            @RequestParam(required = false) String marketId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Integer limit
    ) {
        return tradeQueryService.list(botId, strategyId, marketId, status, mode, limit);
    }

    @GetMapping("/{id}")
    public TradeDetailResponse get(@PathVariable Long id) {
        return tradeQueryService.get(id);
    }
}
