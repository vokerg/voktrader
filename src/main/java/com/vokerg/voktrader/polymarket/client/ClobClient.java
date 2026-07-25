package com.vokerg.voktrader.polymarket.client;

import com.vokerg.voktrader.marketdata.TickSizeService;
import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import com.vokerg.voktrader.polymarket.dto.ClobMarketInfoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClobClient {

    @Qualifier("clobWebClient")
    private final WebClient clobWebClient;
    private final TickSizeService tickSizeService;

    public Mono<OrderBookDto> getOrderBook(String tokenId) {
        return clobWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/book")
                        .queryParam("token_id", tokenId)
                        .build())
                .retrieve()
                .bodyToMono(OrderBookDto.class)
                .doOnSubscribe(s -> log.debug("Fetching order book tokenId={}", tokenId))
                .doOnNext(book -> tickSizeService.recordRestBook(
                        tokenId,
                        book.market(),
                        book.tickSize(),
                        parseTimestamp(book.timestamp())
                ))
                .doOnNext(book -> log.debug(
                        "Order book tokenId={} bestBid={} bestAsk={} spread={} tickSize={}",
                        tokenId,
                        book.bestBid().orElse(null),
                        book.bestAsk().orElse(null),
                        book.spread().orElse(null),
                        book.tickSize()
                ))
                .doOnError(e -> log.error("Failed to fetch order book tokenId={}", tokenId, e));
    }

    public Mono<ClobMarketInfoDto> getClobMarketInfo(String conditionId) {
        return clobWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/clob-markets/{conditionId}")
                        .build(conditionId))
                .retrieve()
                .bodyToMono(ClobMarketInfoDto.class)
                .doOnSubscribe(s -> log.debug("Fetching CLOB market info conditionId={}", conditionId))
                .doOnNext(info -> log.debug(
                        "CLOB market info conditionId={} feeRate={}",
                        conditionId,
                        info.platformFeeRate()
                ))
                .doOnError(e -> log.error("Failed to fetch CLOB market info conditionId={}", conditionId, e));
    }

    private Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            long raw = Long.parseLong(value);
            return value.length() <= 10 ? Instant.ofEpochSecond(raw) : Instant.ofEpochMilli(raw);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException invalidTimestamp) {
                return Instant.now();
            }
        }
    }
}
