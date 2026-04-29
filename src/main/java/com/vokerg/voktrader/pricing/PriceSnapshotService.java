package com.vokerg.voktrader.pricing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceSnapshotService {

    private final PriceSnapshotRepository priceSnapshotRepository;

    public void saveSnapshot(
            String marketId,
            Duration remaining,
            OutcomePrice up,
            OutcomePrice down,
            Instant capturedAt
    ) {
        if (up == null || down == null || capturedAt == null) {
            return;
        }

        priceSnapshotRepository.save(PriceSnapshotEntity.snapshot(
                parseMarketId(marketId),
                remaining == null ? null : remaining.getSeconds(),
                up,
                down,
                capturedAt
        ));
    }

    private Long parseMarketId(String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return null;
        }

        try {
            return Long.parseLong(marketId);
        } catch (NumberFormatException e) {
            log.warn("Saving price snapshot without numeric marketId because marketId is not numeric: {}", marketId);
            return null;
        }
    }
}
