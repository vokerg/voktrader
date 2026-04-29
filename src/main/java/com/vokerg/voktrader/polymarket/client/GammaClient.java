package com.vokerg.voktrader.polymarket.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class GammaClient {

    private static final String FOUND_MARKET_MARKER = "\uD83D\uDFE2";

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
                .doOnSubscribe(s -> log.info(
                        "{}Fetching active markets limit={} offset={}{}",
                        LogColors.MARKET,
                        limit,
                        offset,
                        LogColors.RESET))
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
                .doOnNext(market -> logFoundMarket("BTC-ish market", market));
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
                .doOnNext(market -> logFoundMarket("BTC Up/Down market", market));
    }

    public Flux<GammaMarketDto> findActiveBitcoinUpDownMarketsFirstPages(int pages, int pageSize) {
        return Flux.range(0, pages)
                .concatMap(page -> findActiveBitcoinUpDownMarkets(pageSize, page * pageSize));
    }

    public Flux<GammaMarketDto> searchBitcoinUpDownMarkets() {
        Instant minEndTime = Instant.now().plusSeconds(60);

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
                .doOnSubscribe(s -> log.info(
                        "{}Searching public-search for bitcoin up or down{}",
                        LogColors.MARKET,
                        LogColors.RESET))
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
                .filter(GammaMarketDto::acceptsOrders)
                .filter(market -> market.endsAfter(minEndTime))
                .filter(market -> market.tokenIds(objectMapper).size() >= 2)
                .sort(Comparator.comparing(GammaMarketDto::endDate))
                .doOnNext(market -> logFoundMarket("future search market", market))
                .doOnError(e -> log.error("Failed to search bitcoin up/down markets", e));
    }

    private void logFoundMarket(String label, GammaMarketDto market) {
        log.info(
                "{}{} FOUND {}: id={} slug={} question={} endDate={} acceptingOrders={} tokens={}{}",
                LogColors.MARKET,
                FOUND_MARKET_MARKER,
                label,
                market.id(),
                market.slug(),
                market.question(),
                market.endDate(),
                market.acceptingOrders(),
                market.tokenIds(objectMapper),
                LogColors.RESET);
    }

    private Flux<GammaMarketDto> extractMarketsFromPublicSearch(JsonNode root) {
        List<GammaMarketDto> result = new ArrayList<>();

        JsonNode events = root.path("events");

        if (!events.isArray()) {
            log.warn(
                    "{}public-search response had no events array{}",
                    LogColors.MARKET,
                    LogColors.RESET);
            return Flux.empty();
        }

        for (JsonNode event : events) {
            log.info(
                    "{}Search event: title={} slug={} active={} closed={}{}",
                    LogColors.MARKET,
                    event.path("title").asText(null),
                    event.path("slug").asText(null),
                    event.path("active").asText(null),
                    event.path("closed").asText(null),
                    LogColors.RESET);

            JsonNode markets = event.path("markets");

            if (!markets.isArray()) {
                continue;
            }

            for (JsonNode marketNode : markets) {
                GammaMarketDto market = objectMapper.convertValue(marketNode, GammaMarketDto.class);

                log.info(
                        "{}Nested market candidate: question={} slug={} active={} closed={} acceptingOrders={} endDate={} tokens={}{}",
                        LogColors.MARKET,
                        market.question(),
                        market.slug(),
                        market.active(),
                        market.closed(),
                        market.acceptingOrders(),
                        market.endDate(),
                        market.tokenIds(objectMapper),
                        LogColors.RESET);

                result.add(market);
            }
        }

        return Flux.fromIterable(result);
    }

    public Mono<GammaMarketDto> getMarketBySlug(String slug) {
        log.info("{}Fetching market by slug: {}{}", LogColors.MARKET, slug, LogColors.RESET);

        return gammaWebClient.get()
                .uri("/markets/slug/{slug}", slug)
                .retrieve()
                .bodyToMono(GammaMarketDto.class)
                .doOnNext(market -> log.info(
                        "{}Slug market candidate: id={} slug={} question={} active={} closed={} acceptingOrders={} endDate={} tokens={}{}",
                        LogColors.MARKET,
                        market.id(),
                        market.slug(),
                        market.question(),
                        market.active(),
                        market.closed(),
                        market.acceptingOrders(),
                        market.endDate(),
                        market.clobTokenIds(),
                        LogColors.RESET
                ))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> {
                    log.debug("No market found for slug={}", slug);
                    return Mono.empty();
                });
    }

    public Mono<GammaMarketDto> getMarketById(String id) {
        log.info("{}Fetching market by id: {}{}", LogColors.MARKET, id, LogColors.RESET);

        return gammaWebClient.get()
                .uri("/markets/{id}", id)
                .retrieve()
                .bodyToMono(GammaMarketDto.class)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> {
                    log.debug("No market found for id={}", id);
                    return Mono.empty();
                });
    }
}
