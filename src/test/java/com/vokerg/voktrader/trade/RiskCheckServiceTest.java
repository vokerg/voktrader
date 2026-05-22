package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final RiskCheckService service = new RiskCheckService(properties, tradeRepository, tradeOrderRepository);

    @Test
    void maxTradesPerMarketIgnoresRejectedTrades() {
        properties.setAllowedStrategyIds(java.util.Set.of("cost-aware-momentum"));
        properties.setMaxOrderUsd(new BigDecimal("1.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(1);

        TradeIntent intent = TradeIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        );

        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("up"),
                eq("cost-aware-momentum"),
                anyCollection()
        )).thenReturn(0L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("cost-aware-momentum"),
                anyCollection()
        )).thenReturn(0L);
        when(tradeOrderRepository.findByClientOrderId("key")).thenReturn(Optional.empty());

        RiskAssessment assessment = service.assess(intent, ExecutionMode.PAPER, 10L, 20L, "key");

        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.checks())
                .filteredOn(check -> "MAX_TRADES_PER_MARKET".equals(check.getCheckName()))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.isPassed()).isTrue();
                    assertThat(check.getObservedValue()).isEqualTo("0");
                });

        ArgumentCaptor<Collection<TradeStatus>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(tradeRepository).countByMarketIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("cost-aware-momentum"),
                statusCaptor.capture()
        );
        Set<String> countedStatuses = statusCaptor.getValue()
                .stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(countedStatuses).containsExactlyInAnyOrder(
                "CREATED",
                "ENTRY_PENDING",
                "PARTIALLY_OPEN",
                "OPEN",
                "EXIT_PENDING",
                "PARTIALLY_CLOSED"
        );
        assertThat(countedStatuses).doesNotContain(
                "RISK_REJECTED",
                "VALIDATION_REJECTED",
                "FAILED",
                "CANCELLED",
                "CANCELLED_BEFORE_FILL"
        );
    }

    @Test
    void activeTradeBlocksMaxTradesPerMarketBeforeExecutionCreatesANewTrade() {
        properties.setAllowedStrategyIds(java.util.Set.of("cost-aware-momentum"));
        properties.setMaxOrderUsd(new BigDecimal("1.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(1);

        TradeIntent intent = TradeIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        );

        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("up"),
                eq("cost-aware-momentum"),
                anyCollection()
        )).thenReturn(1L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("cost-aware-momentum"),
                anyCollection()
        )).thenReturn(1L);
        when(tradeOrderRepository.findByClientOrderId("key")).thenReturn(Optional.empty());

        RiskAssessment assessment = service.assess(intent, ExecutionMode.PAPER, null, null, "key");

        assertThat(assessment.passed()).isFalse();
        assertThat(assessment.checks())
                .filteredOn(check -> "MAX_TRADES_PER_MARKET".equals(check.getCheckName()))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.isPassed()).isFalse();
                    assertThat(check.getObservedValue()).isEqualTo("1");
                });
    }

    @Test
    void liveOpenTradeLimitCountsActiveLiveTradesBeforeCreatingCurrentTrade() {
        properties.setAllowedStrategyIds(Set.of("cost-aware-momentum"));
        properties.setMaxOrderUsd(new BigDecimal("1.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(5);
        properties.setMaxOpenLiveTrades(3);
        properties.setKillSwitchEnabled(false);
        properties.setLiveEnabled(true);

        TradeIntent intent = TradeIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        );

        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("up"),
                eq("cost-aware-momentum"),
                anyCollection()
        )).thenReturn(0L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("cost-aware-momentum"),
                anyCollection()
        )).thenReturn(0L);
        when(tradeOrderRepository.findByClientOrderId("key")).thenReturn(Optional.empty());
        when(tradeRepository.countLiveCapacityTrades(eq(List.of(ExecutionMode.LIVE)), anyCollection(), any(Instant.class)))
                .thenReturn(3L);

        RiskAssessment assessment = service.assess(intent, ExecutionMode.LIVE, null, null, "key");

        assertThat(assessment.passed()).isFalse();
        assertThat(assessment.checks())
                .filteredOn(check -> "MAX_OPEN_LIVE_TRADES".equals(check.getCheckName()))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.isPassed()).isFalse();
                    assertThat(check.getObservedValue()).isEqualTo("3");
                    assertThat(check.getLimitValue()).isEqualTo("3");
                });
    }

    @Test
    void liveOpenTradeLimitIncludesPartialPositionStatuses() {
        properties.setAllowedStrategyIds(Set.of("cost-aware-momentum"));
        properties.setMaxOrderUsd(new BigDecimal("1.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(5);
        properties.setMaxOpenLiveTrades(10);
        properties.setKillSwitchEnabled(false);
        properties.setLiveEnabled(true);

        TradeIntent intent = TradeIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        );

        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(any(), any(), any(), anyCollection())).thenReturn(0L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(any(), any(), anyCollection())).thenReturn(0L);
        when(tradeOrderRepository.findByClientOrderId("key")).thenReturn(Optional.empty());
        when(tradeRepository.countLiveCapacityTrades(eq(List.of(ExecutionMode.LIVE)), anyCollection(), any(Instant.class))).thenReturn(1L);

        service.assess(intent, ExecutionMode.LIVE, null, null, "key");

        ArgumentCaptor<Collection<TradeStatus>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(tradeRepository).countLiveCapacityTrades(eq(List.of(ExecutionMode.LIVE)), statusCaptor.capture(), any(Instant.class));
        assertThat(statusCaptor.getValue())
                .contains(TradeStatus.PARTIALLY_OPEN, TradeStatus.PARTIALLY_CLOSED);
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                Instant.now().plusSeconds(60),
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

    private OutcomePrice price() {
        return new OutcomePrice(
                "up",
                "Up",
                new BigDecimal("0.59"),
                new BigDecimal("0.61"),
                new BigDecimal("0.02"),
                Instant.now().minusMillis(100)
        );
    }
}
