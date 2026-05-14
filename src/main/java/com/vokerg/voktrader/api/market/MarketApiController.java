package com.vokerg.voktrader.api.market;

import com.vokerg.voktrader.api.market.dto.MarketDetailResponse;
import com.vokerg.voktrader.api.market.dto.MarketSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/markets")
public class MarketApiController {
    private final MarketQueryService marketQueryService;

    public MarketApiController(MarketQueryService marketQueryService) {
        this.marketQueryService = marketQueryService;
    }

    @GetMapping
    public List<MarketSummaryResponse> list(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean acceptingOrders,
            @RequestParam(required = false) String trackingStatus,
            @RequestParam(required = false) String resolutionStatus,
            @RequestParam(required = false) Integer limit
    ) {
        return marketQueryService.list(active, acceptingOrders, trackingStatus, resolutionStatus, limit);
    }

    @GetMapping("/{polymarketMarketId}")
    public MarketDetailResponse get(@PathVariable String polymarketMarketId) {
        return marketQueryService.getDetail(polymarketMarketId);
    }
}
