package com.vokerg.voktrader.dashboard;

import com.vokerg.voktrader.market.MarketDiscoveryService;
import com.vokerg.voktrader.market.MarketEntity;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/markets")
public class MarketController {

    private final MarketDiscoveryService marketDiscoveryService;

    public MarketController(MarketDiscoveryService marketDiscoveryService) {
        this.marketDiscoveryService = marketDiscoveryService;
    }

    @PostMapping("/discover")
    public List<MarketEntity> discover() {
        return marketDiscoveryService.discoverActiveBtcMarkets();
    }
}
