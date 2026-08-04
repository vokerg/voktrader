package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.RiskSeverity;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskCheckServiceTest {
    private final TradingProperties properties = new TradingProperties();
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final LiveArmService liveArmService = mock(LiveArmService.class);
    private final RiskCheckService service = new RiskCheckService(
            properties, tradeRepository, tradeOrderRepository, liveArmService);

    @BeforeEach
    void setUp() {
        properties.setAllowedStrategyIds(Set.of("cost-aware-momentum"));
        properties.setMaxOrderUsd(new BigDecimal("5.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(5);
        properties.setMaxOpenLiveTrades(3);
        properties.setKillSwitchEnabled(false);
        properties.setLiveEnabled(true);
        when(liveArmService.status()).thenReturn(armedStatus());
        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                any(), any(), any(), anyCollection())).thenReturn(0L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(
                any(), any(), anyCollection())).thenReturn(0L);
        when(tradeOrderRepository.findByClientOrderId(any())).thenReturn(Optional.empty());
        when(tradeRepository.countLiveCapacityTrades(
                eq(List.of(ExecutionMode.LIVE)), anyCollection(), any(Instant.class))).thenReturn(0L);
    }

    @Test
    void typedAssessmentCarriesCorrelationOnEveryCheckAndSeparatesInfo() {
        EntryRiskRequest request = EntryRiskRequest.of(entryIntent(market(900)), ExecutionMode.PAPER);

        RiskAssessment assessment = service.assessEntry(request);

        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.correlationId()).isEqualTo(request.correlationId());
        assertThat(assessment.checks()).isNotEmpty().allSatisfy(check ->
                assertThat(check.getCorrelationId()).isEqualTo(request.correlationId()));
        assertThat(assessment.informationalChecks()).isNotEmpty();
        assertThat(assessment.blockingChecks()).isEmpty();
        assertThat(assessment.warningChecks()).isEmpty();
    }

    @Test
    void unknownExpiryProducesWarningWithoutAcceptingAHiddenBlock() {
        EntryRiskRequest request = EntryRiskRequest.of(entryIntent(market(null)), ExecutionMode.PAPER);

        RiskAssessment assessment = service.assessEntry(request);

        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.warningChecks())
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.getCheckName()).isEqualTo("EXPIRY_GUARD");
                    assertThat(check.getSeverity()).isEqualTo(RiskSeverity.WARN);
                    assertThat(check.isPassed()).isFalse();
                });
    }

    @Test
    void duplicateActiveTradeBlocksBeforeExecutionCreatesAnotherTrade() {
        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                eq("market-id"), eq("up"), eq("cost-aware-momentum"), anyCollection())).thenReturn(1L);

        RiskAssessment assessment = service.assessEntry(
                EntryRiskRequest.of(entryIntent(market(900)), ExecutionMode.PAPER));

        assertThat(assessment.passed()).isFalse();
        assertThat(assessment.firstBlockMessage()).contains("active trade");
        assertThat(assessment.blockingChecks())
                .extracting(check -> check.getCheckName())
                .contains("DUPLICATE_OPEN_TRADE");
    }

    @Test
    void liveAssessmentIncludesUnarmedBlockAndCountsPartialExposureStatuses() {
        when(liveArmService.status()).thenReturn(unarmedStatus());

        RiskAssessment assessment = service.assessEntry(
                EntryRiskRequest.of(entryIntent(market(900)), ExecutionMode.LIVE));

        assertThat(assessment.passed()).isFalse();
        assertThat(assessment.blockingChecks())
                .filteredOn(check -> "LIVE_ARM".equals(check.getCheckName()))
                .singleElement()
                .satisfies(check -> assertThat(check.getMessage()).contains("live arm is not active"));

        ArgumentCaptor<Collection<TradeStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(tradeRepository).countLiveCapacityTrades(
                eq(List.of(ExecutionMode.LIVE)), statuses.capture(), any(Instant.class));
        assertThat(statuses.getValue()).contains(TradeStatus.PARTIALLY_OPEN, TradeStatus.PARTIALLY_CLOSED);
    }

    @Test
    void directCompatibilityBuyWithoutBoundaryContextFailsClosed() {
        TradeIntent rawBuy = entryIntent(market(900)).tradeIntent();

        RiskAssessment assessment = service.assess(
                rawBuy, ExecutionMode.PAPER, null, null, "legacy-key");

        assertThat(assessment.passed()).isFalse();
        assertThat(assessment.blockingChecks())
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.getCheckName()).isEqualTo("ENTRY_RISK_BOUNDARY_REQUIRED");
                    assertThat(check.getCorrelationId()).isEqualTo("legacy-key");
                });
    }

    private EntryIntent entryIntent(GammaMarketDto market) {
        return EntryIntent.buy(
                market,
                new OutcomePrice("up", "Up", new BigDecimal("0.59"), new BigDecimal("0.61"),
                        new BigDecimal("0.02"), Instant.now().minusMillis(100)),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        );
    }

    private GammaMarketDto market(Integer secondsToExpiry) {
        return new GammaMarketDto(
                "market-id",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                secondsToExpiry == null ? null : Instant.now().plusSeconds(secondsToExpiry),
                true,
                false,
                true,
                false,
                null,
                null,
                null,
                null
        );
    }

    private LiveArmService.LiveArmStatus armedStatus() {
        return new LiveArmService.LiveArmStatus(
                true, Instant.now(), Instant.now().plusSeconds(900), "0xexpected",
                true, true, true, true, List.of(), List.of());
    }

    private LiveArmService.LiveArmStatus unarmedStatus() {
        return new LiveArmService.LiveArmStatus(
                false, null, null, null,
                true, true, true, false, List.of(), List.of("live arm is not active"));
    }
}
