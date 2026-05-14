package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExitExecutionModeResolverTest {
    private static final String LIVE_ORDER_ID = "0xec019dfad0d11eedca8c3c47071f4a279dcd74ef548fc7969721f1e7b7a268e7";

    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final ExitExecutionModeResolver resolver = new ExitExecutionModeResolver(tradeRepository, tradeOrderRepository);

    @Test
    void exchangeOrderIdAloneMakesOpenTradeLiveBacked() {
        TradeIntent entryIntent = TradeIntent.buy(
                67L,
                market(),
                price("down", "Down", "0.50", "0.51"),
                new BigDecimal("2.55"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.51"),
                "MK_GTD_EDGE_LIVE_TINY_A",
                "mk-gtd-edge-live-tiny-a-entry",
                "entry"
        );
        TradeEntity trade = TradeEntity.fromIntent(entryIntent, ExecutionMode.PAPER);
        ReflectionTestUtils.setField(trade, "id", 5308L);
        trade.markOpen(new BigDecimal("0.51"), new BigDecimal("5"), new BigDecimal("2.55"), BigDecimal.ZERO, Instant.parse("2026-05-13T18:26:46Z"));

        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5308L, entryIntent, ExecutionMode.PAPER, TradeVenue.PAPER_SIM, "entry-local");
        ReflectionTestUtils.setField(entryOrder, "exchangeOrderId", LIVE_ORDER_ID);
        ReflectionTestUtils.setField(entryOrder, "remoteOrderId", null);

        TradeIntent exitIntent = TradeIntent.sell(
                67L,
                market(),
                price("down", "Down", "0.40", "0.41"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.40"),
                "MK_GTD_EDGE_LIVE_TINY_A",
                "book-pressure-flips",
                "strategy-v2 exit strategy=MK_GTD_EDGE_LIVE_TINY_A rule=book-pressure-flips outcome=Down"
        );

        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_LIVE_TINY_A",
                "market-id",
                "down",
                TradeStatus.OPEN
        )).thenReturn(Optional.of(trade));
        when(tradeOrderRepository.findByTradeId(5308L)).thenReturn(List.of(entryOrder));

        ExitExecutionModeResolver.ExitExecutionContext context = resolver.resolve(exitIntent, ExecutionMode.PAPER);

        assertThat(context.liveBacked()).isTrue();
        assertThat(context.mode()).isEqualTo(ExecutionMode.LIVE_TINY);
        assertThat(context.openTrade()).isSameAs(trade);
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "market-id",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                Instant.parse("2026-04-30T10:05:00Z"),
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

    private OutcomePrice price(String tokenId, String outcome, String bid, String ask) {
        BigDecimal bidValue = new BigDecimal(bid);
        BigDecimal askValue = new BigDecimal(ask);
        return new OutcomePrice(
                tokenId,
                outcome,
                bidValue,
                askValue,
                askValue.subtract(bidValue),
                Instant.parse("2026-04-30T10:00:01Z")
        );
    }
}
