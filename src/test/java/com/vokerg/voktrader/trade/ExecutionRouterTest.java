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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionRouterTest {
    private final TradingProperties properties = new TradingProperties();
    private final PaperExecutionService paperExecutionService = mock(PaperExecutionService.class);
    private final LiveShadowExecutionService liveShadowExecutionService = mock(LiveShadowExecutionService.class);
    private final LiveExecutionService liveExecutionService = mock(LiveExecutionService.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final ExecutionRouter router = new ExecutionRouter(
            properties,
            paperExecutionService,
            liveShadowExecutionService,
            liveExecutionService,
            tradeRepository,
            tradeOrderRepository
    );

    @Test
    void paperModeSellForLiveBackedTradeIsForcedToLiveExecutor() {
        properties.setMode(ExecutionMode.PAPER);
        TradeIntent entryIntent = TradeIntent.buy(
                67L,
                market(),
                price("up", "Up", "0.54", "0.55"),
                new BigDecimal("2.75"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.55"),
                "MK_GTD_EDGE_LIVE_TINY_A",
                "mk-gtd-edge-live-tiny-a-entry",
                "entry"
        );
        TradeEntity liveTrade = TradeEntity.fromIntent(entryIntent, ExecutionMode.LIVE_TINY);
        ReflectionTestUtils.setField(liveTrade, "id", 5306L);
        liveTrade.markOpen(new BigDecimal("0.55"), new BigDecimal("5"), new BigDecimal("2.75"), BigDecimal.ZERO, Instant.parse("2026-05-13T18:26:46Z"));
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5306L, entryIntent, ExecutionMode.LIVE_TINY, TradeVenue.POLYMARKET, "entry-local");
        entryOrder.markSubmitting("entry-local", "{}");
        entryOrder.markFilled("0xbd672edc77c3e2616ca6a96e9f1f8363abc0bb2da286ce95d8991d798c313832", new BigDecimal("0.55"), new BigDecimal("5"), new BigDecimal("2.75"));
        TradeIntent exitIntent = TradeIntent.sell(
                67L,
                market(),
                price("up", "Up", "0.52", "0.53"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.52"),
                "MK_GTD_EDGE_LIVE_TINY_A",
                "book-pressure-flips",
                "strategy-v2 exit strategy=MK_GTD_EDGE_LIVE_TINY_A rule=book-pressure-flips outcome=Up"
        );
        TradeExecutionResult liveResult = TradeExecutionResult.accepted(
                ExecutionMode.LIVE_TINY,
                5306L,
                6353L,
                TradeStatus.CLOSED,
                TradeOrderStatus.FILLED,
                "live exit filled"
        );

        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_LIVE_TINY_A",
                "market-id",
                "up",
                TradeStatus.OPEN
        )).thenReturn(Optional.of(liveTrade));
        when(tradeOrderRepository.findByTradeId(5306L)).thenReturn(List.of(entryOrder));
        when(liveExecutionService.execute(exitIntent, ExecutionMode.LIVE_TINY)).thenReturn(liveResult);

        TradeExecutionResult result = router.route(exitIntent);

        assertThat(result).isSameAs(liveResult);
        verify(liveExecutionService).execute(exitIntent, ExecutionMode.LIVE_TINY);
        verifyNoInteractions(paperExecutionService);
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
