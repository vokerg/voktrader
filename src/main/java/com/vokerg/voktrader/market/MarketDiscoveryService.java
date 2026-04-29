package com.vokerg.voktrader.market;

import com.vokerg.voktrader.polymarket.client.GammaClient;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MarketDiscoveryService {

    private final GammaClient gammaClient;
    private final MarketPersistenceService marketPersistenceService;

    public MarketDiscoveryService(
            GammaClient gammaClient,
            MarketPersistenceService marketPersistenceService
    ) {
        this.gammaClient = gammaClient;
        this.marketPersistenceService = marketPersistenceService;
    }

    public List<MarketEntity> discoverActiveBtcMarkets() {
        return gammaClient.findActiveBitcoinMarketsFirstPages(3, 100)
                .map(marketPersistenceService::saveOrUpdate)
                .collectList()
                .block();
    }
}
