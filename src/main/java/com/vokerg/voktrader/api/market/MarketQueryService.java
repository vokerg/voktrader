package com.vokerg.voktrader.api.market;

import com.vokerg.voktrader.api.market.dto.MarketDetailResponse;
import com.vokerg.voktrader.api.market.dto.MarketOrderBookSnapshotResponse;
import com.vokerg.voktrader.api.market.dto.MarketPriceSnapshotResponse;
import com.vokerg.voktrader.api.market.dto.MarketSummaryResponse;
import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.market.MarketRepository;
import com.vokerg.voktrader.market.MarketResolutionStatus;
import com.vokerg.voktrader.market.MarketTrackingStatus;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotRepository;
import com.vokerg.voktrader.marketdata.persistence.PriceSnapshotRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class MarketQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int RECENT_SNAPSHOTS_LIMIT = 20;

    private final MarketRepository marketRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final MarketDepthSnapshotRepository marketDepthSnapshotRepository;

    public MarketQueryService(
            MarketRepository marketRepository,
            PriceSnapshotRepository priceSnapshotRepository,
            MarketDepthSnapshotRepository marketDepthSnapshotRepository
    ) {
        this.marketRepository = marketRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.marketDepthSnapshotRepository = marketDepthSnapshotRepository;
    }

    public List<MarketSummaryResponse> list(
            Boolean active,
            Boolean acceptingOrders,
            String trackingStatus,
            String resolutionStatus,
            Integer limit
    ) {
        int size = normalizeLimit(limit);
        Specification<MarketEntity> specification = specification(
                eq("active", active),
                eq("acceptingOrders", acceptingOrders),
                eqEnum("trackingStatus", trackingStatus, MarketTrackingStatus.class),
                eqEnum("resolutionStatus", resolutionStatus, MarketResolutionStatus.class)
        );
        return marketRepository.findAll(specification, PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "lastSeenAt"))).stream()
                .map(this::toSummary)
                .toList();
    }

    public MarketSummaryResponse get(String polymarketMarketId) {
        MarketEntity market = marketRepository.findByPolymarketMarketId(polymarketMarketId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown market id: " + polymarketMarketId));
        return toSummary(market);
    }

    public MarketDetailResponse getDetail(String polymarketMarketId) {
        MarketEntity market = marketRepository.findByPolymarketMarketId(polymarketMarketId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown market id: " + polymarketMarketId));
        
        MarketPriceSnapshotResponse latestPrice = priceSnapshotRepository.findFirstByMarketOrderByCapturedAtDesc(market)
                .map(MarketPriceSnapshotResponse::from)
                .orElse(null);
        MarketOrderBookSnapshotResponse latestOrderBook = marketDepthSnapshotRepository.findFirstByMarketOrderByCapturedAtDesc(market)
                .map(MarketOrderBookSnapshotResponse::from)
                .orElse(null);
        
        List<MarketPriceSnapshotResponse> recentPrices = priceSnapshotRepository.findByMarket(market, PageRequest.of(0, RECENT_SNAPSHOTS_LIMIT, Sort.by(Sort.Direction.DESC, "capturedAt")))
                .stream()
                .map(MarketPriceSnapshotResponse::from)
                .toList();
        
        List<MarketOrderBookSnapshotResponse> recentOrderBooks = marketDepthSnapshotRepository.findByMarket(market, PageRequest.of(0, RECENT_SNAPSHOTS_LIMIT, Sort.by(Sort.Direction.DESC, "capturedAt")))
                .stream()
                .map(MarketOrderBookSnapshotResponse::from)
                .toList();

        return MarketDetailResponse.from(market, latestPrice, latestOrderBook, recentPrices, recentOrderBooks);
    }

    private MarketSummaryResponse toSummary(MarketEntity market) {
        MarketPriceSnapshotResponse latestPrice = priceSnapshotRepository.findFirstByMarketOrderByCapturedAtDesc(market)
                .map(MarketPriceSnapshotResponse::from)
                .orElse(null);
        MarketOrderBookSnapshotResponse latestOrderBook = marketDepthSnapshotRepository.findFirstByMarketOrderByCapturedAtDesc(market)
                .map(MarketOrderBookSnapshotResponse::from)
                .orElse(null);
        return MarketSummaryResponse.from(market, latestPrice, latestOrderBook);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private Specification<MarketEntity> eq(String field, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }

    private <E extends Enum<E>> Specification<MarketEntity> eqEnum(String field, String rawValue, Class<E> enumType) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        E parsed = Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        return eq(field, parsed);
    }

    @SafeVarargs
    private final Specification<MarketEntity> specification(Specification<MarketEntity>... parts) {
        Specification<MarketEntity> combined = null;
        for (Specification<MarketEntity> part : parts) {
            if (part == null) {
                continue;
            }
            combined = combined == null ? Specification.where(part) : combined.and(part);
        }
        return combined;
    }
}
