package com.vokerg.voktrader.paper;

import com.vokerg.voktrader.common.LogColors;
import com.vokerg.voktrader.polymarket.client.ClobClient;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalService {

    private static final Duration FEE_FETCH_TIMEOUT = Duration.ofSeconds(5);

    private final SignalRepository signalRepository;
    private final ClobClient clobClient;
    private final PaperTradeFeeCalculator paperTradeFeeCalculator;

    @Transactional
    public Optional<SignalEntity> createPaperBuySignal(
            GammaMarketDto market,
            OutcomePrice outcomePrice,
            BigDecimal paperSizeUsd,
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

        if (paperSizeUsd == null || paperSizeUsd.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        boolean alreadyExists = signalRepository.existsByMarketIdAndTokenIdAndRuleNameAndSignalType(
                market.id(),
                outcomePrice.tokenId(),
                ruleName,
                SignalType.PAPER
        );

        if (alreadyExists) {
            return Optional.empty();
        }

        BigDecimal feeRate = fetchFeeRate(market);
        Instant createdAt = Instant.now();
        Long snapshotAgeMs = outcomePrice.updatedAt() == null
                ? null
                : Math.max(0, Duration.between(outcomePrice.updatedAt(), createdAt).toMillis());
        PaperTradeFeeCalculator.EntryFees entryFees = paperTradeFeeCalculator.calculateEntry(
                paperSizeUsd,
                entryPrice,
                feeRate
        );

        SignalEntity signal = SignalEntity.openPaperBuySignal(
                market.id(),
                market.slug(),
                market.question(),
                outcomePrice.outcome(),
                outcomePrice.tokenId(),
                entryPrice,
                paperSizeUsd,
                entryFees.grossPaperShares(),
                entryFees.feeRate(),
                entryFees.entryFeeUsd(),
                entryFees.netPaperShares(),
                outcomePrice.bid(),
                outcomePrice.ask(),
                outcomePrice.spread(),
                outcomePrice.updatedAt(),
                snapshotAgeMs,
                ruleName,
                reason,
                createdAt,
                market.endDate()
        );

        SignalEntity saved = signalRepository.save(signal);

        Duration remaining = market.endDate() == null
                ? null
                : Duration.between(Instant.now(), market.endDate());

        log.info(
                "{}PAPER SIGNAL SAVED: id={} rule={} marketId={} outcome={} tokenId={} entryPrice={} sizeUsd={} shares={} decisionBid={} decisionAsk={} snapshotAgeMs={} remaining={} reason={}{}",
                LogColors.TRADE,
                saved.getId(),
                saved.getRuleName(),
                saved.getMarketId(),
                saved.getOutcome(),
                saved.getTokenId(),
                saved.getEntryPrice(),
                saved.getSizeUsd(),
                saved.getShares(),
                saved.getDecisionBid(),
                saved.getDecisionAsk(),
                saved.getSnapshotAgeMs(),
                remaining,
                saved.getReason(),
                LogColors.RESET
        );

        return Optional.of(saved);
    }

    private BigDecimal fetchFeeRate(GammaMarketDto market) {
        if (market.conditionId() == null || market.conditionId().isBlank()) {
            return BigDecimal.ZERO;
        }

        try {
            return clobClient.getClobMarketInfo(market.conditionId())
                    .map(info -> info.platformFeeRate())
                    .block(FEE_FETCH_TIMEOUT);
        } catch (Exception e) {
            log.warn(
                    "{}Could not fetch CLOB fee data for marketId={} conditionId={}; assuming zero paper fee{}",
                    LogColors.TRADE,
                    market.id(),
                    market.conditionId(),
                    LogColors.RESET,
                    e
            );
            return BigDecimal.ZERO;
        }
    }

    @Transactional
    public Optional<SignalEntity> sellOpenPaperSignal(
            Long signalId,
            OutcomePrice outcomePrice,
            String reason
    ) {
        if (signalId == null || outcomePrice == null) {
            return Optional.empty();
        }

        BigDecimal exitPrice = outcomePrice.bid();

        if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        SignalEntity signal = signalRepository
                .findByIdAndStatusAndSignalType(signalId, SignalStatus.OPEN, SignalType.PAPER)
                .orElse(null);

        if (signal == null) {
            return Optional.empty();
        }

        if (!signal.getTokenId().equals(outcomePrice.tokenId())) {
            log.warn(
                    "{}Refusing to sell paper signal id={} because token mismatch: signalToken={} priceToken={}{}",
                    LogColors.TRADE,
                    signal.getId(),
                    signal.getTokenId(),
                    outcomePrice.tokenId(),
                    LogColors.RESET
            );
            return Optional.empty();
        }

        BigDecimal exitFeeUsd = paperTradeFeeCalculator.calculateFee(
                signal.getShares(),
                exitPrice,
                signal.getFeeRate()
        );

        signal.sell(exitPrice, exitFeeUsd, reason, Instant.now());

        log.info(
                "{}PAPER SIGNAL SOLD: id={} rule={} marketId={} outcome={} tokenId={} entryPrice={} exitPrice={} sizeUsd={} shares={} exitFeeUsd={} pnlUsd={} reason={}{}",
                LogColors.TRADE,
                signal.getId(),
                signal.getRuleName(),
                signal.getMarketId(),
                signal.getOutcome(),
                signal.getTokenId(),
                signal.getEntryPrice(),
                signal.getExitPrice(),
                signal.getSizeUsd(),
                signal.getShares(),
                signal.getExitFeeUsd(),
                signal.getPnlUsd(),
                signal.getExitReason(),
                LogColors.RESET
        );

        return Optional.of(signal);
    }

    @Transactional
    public void resolveMarket(String marketId, String winningOutcome) {
        if (marketId == null || winningOutcome == null) {
            return;
        }

        List<SignalEntity> openSignals = signalRepository.findByMarketIdAndStatusAndSignalType(
                marketId,
                SignalStatus.OPEN,
                SignalType.PAPER
        );

        if (openSignals.isEmpty()) {
            log.info(
                    "{}No OPEN paper signals to resolve for marketId={}{}",
                    LogColors.TRADE,
                    marketId,
                    LogColors.RESET);
            return;
        }

        for (SignalEntity signal : openSignals) {
            signal.resolve(winningOutcome, Instant.now());

            log.info(
                    "{}PAPER SIGNAL RESOLVED: id={} marketId={} outcome={} winningOutcome={} status={} entryPrice={} sizeUsd={} shares={} pnlUsd={}{}",
                    LogColors.TRADE,
                    signal.getId(),
                    signal.getMarketId(),
                    signal.getOutcome(),
                    signal.getWinningOutcome(),
                    signal.getStatus(),
                    signal.getEntryPrice(),
                    signal.getSizeUsd(),
                    signal.getShares(),
                    signal.getPnlUsd(),
                    LogColors.RESET
            );
        }
    }

    @Transactional(readOnly = true)
    public List<SignalEntity> allSignals() {
        return signalRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<SignalEntity> openPaperSignals() {
        return signalRepository.findByStatusAndSignalType(SignalStatus.OPEN, SignalType.PAPER);
    }

    @Transactional(readOnly = true)
    public List<SignalEntity> openPaperSignals(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) {
            return openPaperSignals();
        }

        return signalRepository.findByStatusAndRuleNameAndSignalType(
                SignalStatus.OPEN,
                ruleName,
                SignalType.PAPER
        );
    }
}
