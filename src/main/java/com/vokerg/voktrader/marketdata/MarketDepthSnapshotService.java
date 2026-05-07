package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.market.MarketRepository;
import com.vokerg.voktrader.trade.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDepthSnapshotService {
    private final MarketDepthSnapshotRepository repository;
    private final MarketDepthSnapshotLevelRepository levelRepository;
    private final MarketRepository marketRepository;
    private final TradingProperties tradingProperties;
    private final MarketDepthSnapshotProperties properties;

    public void saveSnapshots(String marketId, Duration remaining, OrderBookState orderBookState, Instant capturedAt) {
        if (orderBookState == null || capturedAt == null) {
            return;
        }

        MarketEntity market = findMarket(marketId);
        Long numericMarketId = parseMarketId(marketId);
        Long remainingSeconds = remaining == null ? null : remaining.getSeconds();

        orderBookState.allByTokenId().values().forEach(book -> {
            repository.save(MarketDepthSnapshotEntity.snapshot(
                    market,
                    numericMarketId,
                    remainingSeconds,
                    book,
                    properties.nearTopRange(),
                    tradingProperties.getMaxOrderUsd(),
                    tradingProperties.getMaxPriceAgeMs(),
                    capturedAt
            ));
            saveLevels(market, numericMarketId, remainingSeconds, book, capturedAt);
        });
    }

    private void saveLevels(
            MarketEntity market,
            Long numericMarketId,
            Long remainingSeconds,
            OutcomeOrderBook book,
            Instant capturedAt
    ) {
        int levelsPerSide = properties.levelsPerSide();
        saveLevels(market, numericMarketId, remainingSeconds, book, OrderBookSide.BUY, book.bids(), levelsPerSide, capturedAt);
        saveLevels(market, numericMarketId, remainingSeconds, book, OrderBookSide.SELL, book.asks(), levelsPerSide, capturedAt);
    }

    private void saveLevels(
            MarketEntity market,
            Long numericMarketId,
            Long remainingSeconds,
            OutcomeOrderBook book,
            OrderBookSide side,
            java.util.List<OrderBookLevel> levels,
            int levelsPerSide,
            Instant capturedAt
    ) {
        int limit = Math.min(levelsPerSide, levels.size());
        for (int i = 0; i < limit; i++) {
            levelRepository.save(MarketDepthSnapshotLevelEntity.snapshot(
                    market,
                    numericMarketId,
                    remainingSeconds,
                    book,
                    side,
                    i + 1,
                    levels.get(i),
                    capturedAt
            ));
        }
    }

    private MarketEntity findMarket(String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return null;
        }
        return marketRepository.findByPolymarketMarketId(marketId).orElse(null);
    }

    private Long parseMarketId(String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(marketId);
        } catch (NumberFormatException e) {
            log.warn("Saving market depth snapshot without numeric marketId because marketId is not numeric: {}", marketId);
            return null;
        }
    }
}
