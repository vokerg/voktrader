package com.vokerg.voktrader.polymarket.client;

import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClobClient {

    @Qualifier("clobWebClient")
    private final WebClient clobWebClient;

    public Mono<OrderBookDto> getOrderBook(String tokenId) {
        return clobWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/book")
                        .queryParam("token_id", tokenId)
                        .build())
                .retrieve()
                .bodyToMono(OrderBookDto.class)
                .doOnSubscribe(s -> log.debug("Fetching order book tokenId={}", tokenId))
                .doOnNext(book -> log.debug(
                        "Order book tokenId={} bestBid={} bestAsk={} spread={}",
                        tokenId,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null),
                        book.spread().orElse(null)
                ))
                .doOnError(e -> log.error("Failed to fetch order book tokenId={}", tokenId, e));
    }
}