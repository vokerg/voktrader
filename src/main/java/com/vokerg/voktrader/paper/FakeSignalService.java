package com.vokerg.voktrader.paper;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FakeSignalService {

    private static final int SHARE_SCALE = 8;

    private final FakeSignalRepository fakeSignalRepository;

    @Transactional
    public Optional<FakeSignalEntity> createBuySignal(
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

        boolean alreadyExists = fakeSignalRepository.existsByMarketIdAndTokenIdAndRuleName(
                market.id(),
                outcomePrice.tokenId(),
                ruleName
        );

        if (alreadyExists) {
            return Optional.empty();
        }

        BigDecimal fakeShares = fakeSizeUsd.divide(
                entryPrice,
                SHARE_SCALE,
                RoundingMode.HALF_UP
        );

        FakeSignalEntity signal = FakeSignalEntity.openBuySignal(
                market.id(),
                market.slug(),
                market.question(),
                outcomePrice.outcome(),
                outcomePrice.tokenId(),
                entryPrice,
                fakeSizeUsd,
                fakeShares,
                ruleName,
                reason,
                Instant.now(),
                market.endDate()
        );

        FakeSignalEntity saved = fakeSignalRepository.save(signal);

        Duration remaining = market.endDate() == null
                ? null
                : Duration.between(Instant.now(), market.endDate());

        log.info(
                "FAKE SIGNAL SAVED: id={} rule={} marketId={} outcome={} tokenId={} entryPrice={} fakeSizeUsd={} fakeShares={} remaining={} reason={}",
                saved.getId(),
                saved.getRuleName(),
                saved.getMarketId(),
                saved.getOutcome(),
                saved.getTokenId(),
                saved.getEntryPrice(),
                saved.getFakeSizeUsd(),
                saved.getFakeShares(),
                remaining,
                saved.getReason()
        );

        return Optional.of(saved);
    }

    @Transactional
    public void resolveMarket(String marketId, String winningOutcome) {
        if (marketId == null || winningOutcome == null) {
            return;
        }

        List<FakeSignalEntity> openSignals = fakeSignalRepository.findByMarketIdAndStatus(
                marketId,
                FakeSignalStatus.OPEN
        );

        if (openSignals.isEmpty()) {
            log.info("No OPEN fake signals to resolve for marketId={}", marketId);
            return;
        }

        for (FakeSignalEntity signal : openSignals) {
            signal.resolve(winningOutcome, Instant.now());

            log.info(
                    "FAKE SIGNAL RESOLVED: id={} marketId={} outcome={} winningOutcome={} status={} entryPrice={} fakeSizeUsd={} fakeShares={} fakePnl={}",
                    signal.getId(),
                    signal.getMarketId(),
                    signal.getOutcome(),
                    signal.getWinningOutcome(),
                    signal.getStatus(),
                    signal.getEntryPrice(),
                    signal.getFakeSizeUsd(),
                    signal.getFakeShares(),
                    signal.getFakePnl()
            );
        }
    }

    @Transactional(readOnly = true)
    public List<FakeSignalEntity> allSignals() {
        return fakeSignalRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<FakeSignalEntity> openSignals() {
        return fakeSignalRepository.findByStatus(FakeSignalStatus.OPEN);
    }
}