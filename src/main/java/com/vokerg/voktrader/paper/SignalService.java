package com.vokerg.voktrader.paper;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.trade.TradeLifecycleService;
import com.vokerg.voktrader.trade.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Compatibility adapter for existing resolution code.
 *
 * The old model treated a signal as the paper position. New code records real trade lifecycle rows in trades/* tables.
 * Keep this class so existing MarketResolutionService can call signalService.resolveMarket(...) without knowing the new model yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalService {
    private final TradeLifecycleService tradeLifecycleService;
    private final SignalRepository signalRepository;
    private final PaperTradeFeeCalculator paperTradeFeeCalculator;
    private final TradingProperties tradingProperties;

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

    @Transactional
    public Optional<SignalEntity> createPaperBuySignal(
            GammaMarketDto market,
            OutcomePrice price,
            BigDecimal paperSizeUsd,
            String ruleName,
            String reason
    ) {
        if (market == null || market.id() == null || price == null || price.tokenId() == null) {
            return Optional.empty();
        }
        if (price.ask() == null || price.ask().compareTo(BigDecimal.ZERO) <= 0 || paperSizeUsd == null) {
            return Optional.empty();
        }

        boolean alreadyExists = signalRepository.existsByMarketIdAndTokenIdAndRuleNameAndSignalType(
                market.id(),
                price.tokenId(),
                ruleName,
                SignalType.PAPER
        );
        if (alreadyExists) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        PaperTradeFeeCalculator.EntryFees fees = paperTradeFeeCalculator.calculateEntry(
                paperSizeUsd,
                price.ask(),
                tradingProperties.getPaperFeeRate()
        );

        SignalEntity signal = SignalEntity.openPaperBuySignal(
                market.id(),
                market.slug(),
                market.question(),
                price.outcome(),
                price.tokenId(),
                price.ask(),
                paperSizeUsd,
                fees.grossPaperShares(),
                fees.feeRate(),
                fees.entryFeeUsd(),
                fees.netPaperShares(),
                price.bid(),
                price.ask(),
                price.spread(),
                price.updatedAt(),
                price.updatedAt() == null ? null : Duration.between(price.updatedAt(), now).toMillis(),
                ruleName,
                reason,
                now,
                market.endDate()
        );

        SignalEntity saved = signalRepository.save(signal);
        log.info(
                "PAPER SIGNAL OPENED: signalId={} rule={} marketId={} outcome={} tokenId={} price={} sizeUsd={} shares={} fee={} reason={}",
                saved.getId(), ruleName, market.id(), price.outcome(), price.tokenId(), price.ask(), paperSizeUsd,
                saved.getShares(), saved.getEntryFeeUsd(), reason);
        return Optional.of(saved);
    }

    @Transactional
    public Optional<SignalEntity> sellOpenPaperSignal(Long signalId, OutcomePrice price, String exitReason) {
        if (signalId == null || price == null || price.bid() == null) {
            return Optional.empty();
        }

        Optional<SignalEntity> found = signalRepository.findByIdAndStatusAndSignalType(
                signalId,
                SignalStatus.OPEN,
                SignalType.PAPER
        );
        if (found.isEmpty()) {
            return Optional.empty();
        }

        SignalEntity signal = found.get();
        if (!signal.getTokenId().equals(price.tokenId())) {
            return Optional.empty();
        }

        BigDecimal exitFeeUsd = paperTradeFeeCalculator.calculateFee(
                signal.getShares(),
                price.bid(),
                signal.getFeeRate()
        );
        signal.sell(price.bid(), exitFeeUsd, exitReason, Instant.now());
        SignalEntity saved = signalRepository.save(signal);
        log.info(
                "PAPER SIGNAL SOLD: signalId={} rule={} marketId={} outcome={} tokenId={} exitPrice={} pnlUsd={} reason={}",
                saved.getId(), saved.getRuleName(), saved.getMarketId(), saved.getOutcome(),
                saved.getTokenId(), saved.getExitPrice(), saved.getPnlUsd(), exitReason);
        return Optional.of(saved);
    }

    @Transactional
    public void resolveMarket(String marketId, String winningOutcome) {
        Instant now = Instant.now();
        for (SignalEntity signal : signalRepository.findByMarketIdAndStatusAndSignalType(
                marketId,
                SignalStatus.OPEN,
                SignalType.PAPER
        )) {
            signal.resolve(winningOutcome, now);
            signalRepository.save(signal);
        }

        tradeLifecycleService.resolveMarket(marketId, winningOutcome);
    }
}
