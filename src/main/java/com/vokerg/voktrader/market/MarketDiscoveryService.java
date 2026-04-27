package com.vokerg.voktrader.market;

import com.vokerg.voktrader.polymarket.client.GammaClient;
import com.vokerg.voktrader.polymarket.mapper.PolymarketMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MarketDiscoveryService {

    private final GammaClient gammaClient;
    private final PolymarketMapper mapper;
    private final MarketRepository marketRepository;

    public MarketDiscoveryService(
            GammaClient gammaClient,
            PolymarketMapper mapper,
            MarketRepository marketRepository
    ) {
        this.gammaClient = gammaClient;
        this.mapper = mapper;
        this.marketRepository = marketRepository;
    }

    public List<MarketEntity> discoverActiveBtcMarkets() {
        return gammaClient.findActiveBitcoinMarketsFirstPages(3, 100)
                .map(mapper::toMarketEntity)
                .map(marketRepository::save)
                .collectList()
                .block();
    }
}
