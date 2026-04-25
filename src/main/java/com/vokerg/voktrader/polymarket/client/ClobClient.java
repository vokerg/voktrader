package com.vokerg.voktrader.polymarket.client;

import com.vokerg.voktrader.config.PolymarketProperties;
import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class ClobClient {

    private final WebClient webClient;

    public ClobClient(WebClient.Builder webClientBuilder, PolymarketProperties properties) {
        this.webClient = webClientBuilder
                .baseUrl(properties.getClobBaseUrl())
                .build();
    }

    public OrderBookDto getOrderBook(String tokenId) {
        // TODO: call CLOB API.
        return new OrderBookDto(tokenId, List.of(), List.of());
    }
}
