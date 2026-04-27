package com.vokerg.voktrader.polymarket.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class GammaClient {

    @Qualifier("gammaWebClient")
    private final WebClient gammaWebClient;

    private final ObjectMapper objectMapper;

    public Flux<GammaMarketDto> listActiveMarkets(int limit, int offset) {
        return gammaWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/markets")
                        .queryParam("active", true)
                        .queryParam("closed", false)
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .build())
                .retrieve()
                .bodyToFlux(GammaMarketDto.class)
                .doOnSubscribe(s -> log.info("Fetching active markets limit={} offset={}", limit, offset))
                .doOnError(e -> log.error("Failed to fetch active markets", e));
    }

    public Flux<GammaMarketDto> findActiveBitcoinMarkets(int limit, int offset) {
        return listActiveMarkets(limit, offset)
                .filter(GammaMarketDto::isActiveOpenMarket)
                .filter(GammaMarketDto::looksLikeBitcoinMarket)
                .filter(market -> {
                    var tokenIds = market.tokenIds(objectMapper);
                    return tokenIds.size() >= 2;
                })
                .doOnNext(market -> log.info(
                        "Found BTC-ish market: id={} slug={} question={} tokens={}",
                        market.id(),
                        market.slug(),
                        market.question(),
                        market.tokenIds(objectMapper)));
    }

    public Flux<GammaMarketDto> findActiveBitcoinMarketsFirstPages(int pages, int pageSize) {
        return Flux.range(0, pages)
                .concatMap(page -> findActiveBitcoinMarkets(pageSize, page * pageSize));
    }

    public Flux<GammaMarketDto> findActiveBitcoinUpDownMarkets(int limit, int offset) {
        return listActiveMarkets(limit, offset)
                .filter(GammaMarketDto::isActiveOpenMarket)
                .filter(GammaMarketDto::looksLikeBitcoinUpDownMarket)
                .filter(market -> {
                    var tokenIds = market.tokenIds(objectMapper);
                    return tokenIds.size() >= 2;
                })
                .doOnNext(market -> log.info(
                        "Found BTC Up/Down market: id={} slug={} question={} endDate={} tokens={}",
                        market.id(),
                        market.slug(),
                        market.question(),
                        market.endDate(),
                        market.tokenIds(objectMapper)));
    }

    public Flux<GammaMarketDto> findActiveBitcoinUpDownMarketsFirstPages(int pages, int pageSize) {
        return Flux.range(0, pages)
                .concatMap(page -> findActiveBitcoinUpDownMarkets(pageSize, page * pageSize));
    }

   public Flux<GammaMarketDto> searchBitcoinUpDownMarkets() {
    return gammaWebClient.get()
            .uri(uriBuilder -> uriBuilder
                    .path("/public-search")
                    .queryParam("q", "bitcoin up or down")
                    .queryParam("events_status", "active")
                    .queryParam("limit_per_type", 10)
                    .queryParam("page", 1)
                    .queryParam("keep_closed_markets", 0)
                    .queryParam("search_profiles", false)
                    .queryParam("cache", false)
                    .build())
            .retrieve()
            .bodyToMono(String.class)
            .doOnSubscribe(s -> log.info("Searching public-search for bitcoin up or down"))
            .flatMapMany(rawJson -> {
                try {
                    JsonNode root = objectMapper.readTree(rawJson);
                    return extractMarketsFromPublicSearch(root);
                } catch (Exception e) {
                    log.error("Failed to parse public-search response: {}", rawJson, e);
                    return Flux.empty();
                }
            })
            .filter(GammaMarketDto::isActiveOpenMarket)
            .filter(market -> market.tokenIds(objectMapper).size() >= 2)
            .doOnNext(market -> log.info(
                    "Search found market: id={} slug={} question={} endDate={} tokens={}",
                    market.id(),
                    market.slug(),
                    market.question(),
                    market.endDate(),
                    market.tokenIds(objectMapper)
            ))
            .doOnError(e -> log.error("Failed to search bitcoin up/down markets", e));
}

    private Flux<GammaMarketDto> extractMarketsFromPublicSearch(JsonNode root) {
        List<GammaMarketDto> result = new ArrayList<>();

        JsonNode events = root.path("events");

        if (!events.isArray()) {
            log.warn("public-search response had no events array");
            return Flux.empty();
        }

        for (JsonNode event : events) {
            log.info(
                    "Search event: title={} slug={} active={} closed={}",
                    event.path("title").asText(null),
                    event.path("slug").asText(null),
                    event.path("active").asText(null),
                    event.path("closed").asText(null));

            JsonNode markets = event.path("markets");

            if (!markets.isArray()) {
                continue;
            }

            for (JsonNode marketNode : markets) {
                GammaMarketDto market = objectMapper.convertValue(marketNode, GammaMarketDto.class);

                log.info(
                        "Nested market candidate: question={} slug={} active={} closed={} acceptingOrders={} clobTokenIds={}",
                        market.question(),
                        market.slug(),
                        market.active(),
                        market.closed(),
                        marketNode.path("acceptingOrders").asText(null),
                        marketNode.path("clobTokenIds"));

                result.add(market);
            }
        }

        return Flux.fromIterable(result);
    }
}
