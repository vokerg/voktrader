package com.vokerg.voktrader.paper;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class FakeSignalService {

    private static final int SHARE_SCALE = 8;

    private final ConcurrentMap<String, FakeSignal> signalsByKey = new ConcurrentHashMap<>();

    public Optional<FakeSignal> createBuySignal(
            GammaMarketDto market,
            OutcomePrice outcomePrice,
            BigDecimal fakeSizeUsd,
            String ruleName,
            String reason
    ) {
        if (market == null || outcomePrice == null) {
            return Optional.empty();
        }

        if (market.id() == null || outcomePrice.tokenId() == null || outcomePrice.outcome() == null) {
            return Optional.empty();
        }

        BigDecimal entryPrice = outcomePrice.ask();

        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        if (fakeSizeUsd == null || fakeSizeUsd.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        String signalKey = signalKey(market.id(), outcomePrice.tokenId(), ruleName);

        BigDecimal fakeShares = fakeSizeUsd.divide(
                entryPrice,
                SHARE_SCALE,
                RoundingMode.HALF_UP
        );

        FakeSignal signal = new FakeSignal(
                market.id(),
                market.question(),
                outcomePrice.outcome(),
                outcomePrice.tokenId(),
                entryPrice,
                fakeSizeUsd,
                fakeShares,
                ruleName,
                reason,
                Instant.now()
        );

        FakeSignal previous = signalsByKey.putIfAbsent(signalKey, signal);

        if (previous != null) {
            return Optional.empty();
        }

        Duration remaining = market.endDate() == null
                ? null
                : Duration.between(Instant.now(), market.endDate());

        log.info(
                "FAKE SIGNAL CREATED: rule={} marketId={} outcome={} tokenId={} entryPrice={} fakeSizeUsd={} fakeShares={} remaining={} reason={}",
                signal.ruleName(),
                signal.marketId(),
                signal.outcome(),
                signal.tokenId(),
                signal.entryPrice(),
                signal.fakeSizeUsd(),
                signal.fakeShares(),
                remaining,
                signal.reason()
        );

        return Optional.of(signal);
    }

    public List<FakeSignal> allSignals() {
        return signalsByKey.values().stream()
                .sorted(Comparator.comparing(FakeSignal::createdAt))
                .toList();
    }

    public boolean hasSignalForMarketOutcomeAndRule(
            String marketId,
            String tokenId,
            String ruleName
    ) {
        return signalsByKey.containsKey(signalKey(marketId, tokenId, ruleName));
    }

    public void clear() {
        signalsByKey.clear();
    }

    private String signalKey(String marketId, String tokenId, String ruleName) {
        return marketId + ":" + tokenId + ":" + ruleName;
    }
}