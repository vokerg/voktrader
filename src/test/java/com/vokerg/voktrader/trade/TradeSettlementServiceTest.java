package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeSettlementServiceTest {
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeEventRepository eventRepository = mock(TradeEventRepository.class);
    private final TradeSettlementService service = new TradeSettlementService(tradeRepository, eventRepository);

    @Test
    void settlesOpenTradesForResolvedMarket() {
        TradeEntity trade = openTrade("market-1", "Yes");
        when(tradeRepository.findByMarketIdAndStatus("market-1", TradeStatus.OPEN)).thenReturn(List.of(trade));

        service.settleOpenTradesForResolvedMarket("market-1", "Yes");

        assertThat(trade.getStatus()).isEqualTo(TradeStatus.RESOLVED);
        assertThat(trade.getWinningOutcome()).isEqualTo("Yes");
        assertThat(trade.getResolvedPnlUsd()).isEqualByComparingTo("6.95");
        verify(tradeRepository).save(trade);
        verify(eventRepository).save(any(TradeEventEntity.class));
    }

    @Test
    void resolvedMarketWithNoOpenTradesSucceeds() {
        when(tradeRepository.findByMarketIdAndStatus("market-1", TradeStatus.OPEN)).thenReturn(List.of());

        service.settleOpenTradesForResolvedMarket("market-1", "Yes");

        verify(tradeRepository, never()).save(any(TradeEntity.class));
        verify(eventRepository, never()).save(any(TradeEventEntity.class));
    }

    private TradeEntity openTrade(String marketId, String outcome) {
        TradeEntity trade = TradeEntity.fromIntent(new TradeIntent(
                1L,
                "strategy",
                "rule",
                marketId,
                "market-slug",
                "Question?",
                "condition",
                "token-yes",
                outcome,
                TradeSide.BUY,
                new BigDecimal("3.00"),
                null,
                TradeOrderType.FOK,
                false,
                new BigDecimal("0.30"),
                new BigDecimal("0.29"),
                new BigDecimal("0.30"),
                new BigDecimal("0.01"),
                new BigDecimal("0.295"),
                Instant.parse("2026-05-18T11:59:55Z"),
                100L,
                Instant.parse("2026-05-18T12:00:00Z"),
                Instant.parse("2026-05-18T12:05:00Z"),
                300L,
                "test"
        ), ExecutionMode.PAPER);
        ReflectionTestUtils.setField(trade, "id", 10L);
        trade.markOpen(
                new BigDecimal("0.30"),
                new BigDecimal("10.00"),
                new BigDecimal("3.00"),
                new BigDecimal("0.05"),
                Instant.parse("2026-05-18T12:00:01Z")
        );
        return trade;
    }
}
