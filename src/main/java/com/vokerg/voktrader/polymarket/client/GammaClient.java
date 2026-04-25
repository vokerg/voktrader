package com.vokerg.voktrader.polymarket.client;

import com.vokerg.voktrader.config.PolymarketProperties;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class GammaClient {

    private final WebClient webClient;
    private final PolymarketProperties properties;

    public GammaClient(WebClient.Builder webClientBuilder, PolymarketProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getGammaBaseUrl())
                .build();
    }

    public List<GammaMarketDto> findActiveBtcMarkets() {
        // TODO: call Gamma API and filter BTC 5-minute markets.
        return List.of();
    }
}
