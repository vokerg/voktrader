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
    private final ExitExecutionModeResolver exitExecutionModeResolver = new ExitExecutionModeResolver(
            tradeRepository,
            tradeOrderRepository
    );
    private final ExecutionRouter router = new ExecutionRouter(
            properties,
            paperExecutionService,
            liveShadowExecutionService,
            liveExecutionService,
            exitExecutionModeResolver
    );

    @Test
    void paperModeSellForLiveBackedTradeIsForcedToLiveExecutor() {
        properties.setMode(ExecutionMode.PAPER);
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
        TradeEntity liveTrade = TradeEntity.fromIntent(entryIntent, ExecutionMode.LIVE_TINY);
        ReflectionTestUtils.setField(liveTrade, "id", 5308L);
        liveTrade.markOpen(new BigDecimal("0.51"), new BigDecimal("5"), new BigDecimal("2.55"), BigDecimal.ZERO, Instant.parse("2026-05-13T18:26:46Z"));
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5308L, entryIntent, ExecutionMode.LIVE_TINY, TradeVenue.POLYMARKET, "entry-local");
        entryOrder.markSubmitting("entry-local", "{}");
        entryOrder.markFilled("0xec019dfad0d11eedca8c3c47071f4a279dcd74ef548fc7969721f1e7b7a268e7", new BigDecimal("0.51"), new BigDecimal("5"), new BigDecimal("2.55"));
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
        TradeExecutionResult liveResult = TradeExecutionResult.accepted(
                ExecutionMode.LIVE_TINY,
                5308L,
                6356L,
                TradeStatus.CLOSED,
                TradeOrderStatus.FILLED,
                "live exit filled"
        );

        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_LIVE_TINY_A",
                "market-id",
                "down",
                TradeStatus.OPEN
        )).thenReturn(Optional.of(liveTrade));
        when(tradeOrderRepository.findByTradeId(5308L)).thenReturn(List.of(entryOrder));
        when(liveExecutionService.execute(exitIntent, ExecutionMode.LIVE_TINY)).thenReturn(liveResult);

        TradeExecutionResult result = router.route(exitIntent);

        assertThat(result).isSameAs(liveResult);
        verify(liveExecutionService).execute(exitIntent, ExecutionMode.LIVE_TINY);
        verifyNoInteractions(paperExecutionService);
        verifyNoInteractions(liveShadowExecutionService);
    }

    @Test
    void paperModeNetTakeProfitSellFor5314ShapeIsForcedToLiveExecutor() {
        properties.setMode(ExecutionMode.PAPER);
        String marketId = "2251671";
        String tokenId = "down-token-5314";
        String remoteOrderId = "0xae1db749b628a6740b4ae97e053271c03331bfcd5cb705c12219f38b3ec30227";
        TradeIntent entryIntent = TradeIntent.buy(
                67L,
                market(marketId),
                price(tokenId, "Down", "0.57", "0.58"),
                new BigDecimal("2.90"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.58"),
                "MK_GTD_EDGE_LIVE_TINY_A",
                "mk-gtd-edge-live-tiny-a-entry",
                "entry"
        );
        TradeEntity liveTrade = TradeEntity.fromIntent(entryIntent, ExecutionMode.LIVE_TINY);
        ReflectionTestUtils.setField(liveTrade, "id", 5314L);
        liveTrade.markOpen(new BigDecimal("0.58"), new BigDecimal("5"), new BigDecimal("2.90"), BigDecimal.ZERO, Instant.parse("2026-05-14T17:06:31.396495Z"));
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5314L, entryIntent, ExecutionMode.LIVE_TINY, TradeVenue.POLYMARKET, "entry-local");
        ReflectionTestUtils.setField(entryOrder, "id", 6362L);
        entryOrder.markSubmitting("entry-local", "{}");
        entryOrder.markFilled(remoteOrderId, new BigDecimal("0.58"), new BigDecimal("5"), new BigDecimal("2.90"));
        TradeIntent exitIntent = TradeIntent.sell(
                67L,
                market(marketId),
                price(tokenId, "Down", "0.63", "0.64"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.63"),
                "MK_GTD_EDGE_LIVE_TINY_A",
                "net-take-profit",
                "strategy-v2 exit rule=net-take-profit outcome=Down"
        );
        TradeExecutionResult liveResult = TradeExecutionResult.accepted(
                ExecutionMode.LIVE_TINY,
                5314L,
                6363L,
                TradeStatus.EXIT_PENDING,
                TradeOrderStatus.SUBMITTED,
                "live exit submitted"
        );

        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_LIVE_TINY_A",
                marketId,
                tokenId,
                TradeStatus.OPEN
        )).thenReturn(Optional.of(liveTrade));
        when(tradeOrderRepository.findByTradeId(5314L)).thenReturn(List.of(entryOrder));
        when(liveExecutionService.execute(exitIntent, ExecutionMode.LIVE_TINY)).thenReturn(liveResult);

        TradeExecutionResult result = router.route(exitIntent);

        assertThat(result).isSameAs(liveResult);
        verify(liveExecutionService).execute(exitIntent, ExecutionMode.LIVE_TINY);
        verifyNoInteractions(paperExecutionService);
        verifyNoInteractions(liveShadowExecutionService);
    }

    private GammaMarketDto market() {
        return market("market-id");
    }

    private GammaMarketDto market(String marketId) {
        return new GammaMarketDto(
                marketId,
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
