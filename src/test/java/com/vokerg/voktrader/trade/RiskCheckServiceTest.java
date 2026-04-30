package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
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
        properties.setAllowedStrategyIds(java.util.Set.of("cost-aware-momentum-paper"));
        properties.setMaxOrderUsd(new BigDecimal("1.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(1);

        TradeIntent intent = TradeIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "cost-aware-momentum-paper",
                "cost-aware-momentum",
                "entry"
        );

        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("up"),
                eq("cost-aware-momentum-paper"),
                anyCollection()
        )).thenReturn(0L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("cost-aware-momentum-paper"),
                anyCollection()
        )).thenReturn(0L);
        when(tradeOrderRepository.findByIdempotencyKey("key")).thenReturn(Optional.empty());

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
                eq("cost-aware-momentum-paper"),
                statusCaptor.capture()
        );
        Set<String> countedStatuses = statusCaptor.getValue()
                .stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(countedStatuses).containsExactlyInAnyOrder(
                "CREATED",
                "ENTRY_PENDING",
                "OPEN",
                "EXIT_PENDING"
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
        properties.setAllowedStrategyIds(java.util.Set.of("cost-aware-momentum-paper"));
        properties.setMaxOrderUsd(new BigDecimal("1.00"));
        properties.setMaxSpread(new BigDecimal("0.03"));
        properties.setMaxPriceAgeMs(1500);
        properties.setMinSecondsToExpiry(30);
        properties.setMaxTradesPerMarket(1);

        TradeIntent intent = TradeIntent.buy(
                market(),
                price(),
                new BigDecimal("1.00"),
                "cost-aware-momentum-paper",
                "cost-aware-momentum",
                "entry"
        );

        when(tradeRepository.countByMarketIdAndTokenIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("up"),
                eq("cost-aware-momentum-paper"),
                anyCollection()
        )).thenReturn(1L);
        when(tradeRepository.countByMarketIdAndStrategyIdAndStatusIn(
                eq("market-id"),
                eq("cost-aware-momentum-paper"),
                anyCollection()
        )).thenReturn(1L);
        when(tradeOrderRepository.findByIdempotencyKey("key")).thenReturn(Optional.empty());

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
