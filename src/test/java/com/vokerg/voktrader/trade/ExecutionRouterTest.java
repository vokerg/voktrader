package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.paper.PaperExecutionService;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionRouterTest {
    private final TradingProperties properties = new TradingProperties();
    private final PaperExecutionService paperExecutionService = mock(PaperExecutionService.class);
    private final LiveExecutionService liveExecutionService = mock(LiveExecutionService.class);
    private final LiveArmService liveArmService = mock(LiveArmService.class);
    private final ExecutionRouter router = new ExecutionRouter(
            properties,
            paperExecutionService,
            liveExecutionService,
            liveArmService
    );

    @Test
    void paperModeRoutesEverythingToPaperExecutor() {
        properties.setMode(ExecutionMode.PAPER);
        TradeIntent intent = sellIntent();
        TradeExecutionResult paperResult = TradeExecutionResult.accepted(
                ExecutionMode.PAPER,
                5317L,
                6363L,
                TradeStatus.CLOSED,
                TradeOrderStatus.FILLED,
                "paper exit filled"
        );
        when(paperExecutionService.execute(intent)).thenReturn(paperResult);

        TradeExecutionResult result = router.route(intent);

        assertThat(result).isSameAs(paperResult);
        verify(paperExecutionService).execute(intent);
        verifyNoInteractions(liveExecutionService, liveArmService);
    }

    @Test
    void unarmedLiveEntryIsRejectedBeforeLiveExecutorCall() {
        properties.setMode(ExecutionMode.LIVE);
        when(liveArmService.status()).thenReturn(unarmedStatus());

        TradeExecutionResult result = router.route(buyIntent());

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).contains("LIVE entry rejected before executor call", "live arm is not active");
        verify(liveArmService).status();
        verifyNoInteractions(liveExecutionService, paperExecutionService);
    }

    @Test
    void armedLiveEntryRoutesToLiveExecutor() {
        properties.setMode(ExecutionMode.LIVE);
        TradeIntent intent = buyIntent();
        TradeExecutionResult liveResult = TradeExecutionResult.accepted(
                ExecutionMode.LIVE,
                5317L,
                6363L,
                TradeStatus.ENTRY_PENDING,
                TradeOrderStatus.SUBMITTED,
                "live entry submitted"
        );
        when(liveArmService.status()).thenReturn(armedStatus());
        when(liveExecutionService.execute(intent, ExecutionMode.LIVE)).thenReturn(liveResult);

        TradeExecutionResult result = router.route(intent);

        assertThat(result).isSameAs(liveResult);
        verify(liveExecutionService).execute(intent, ExecutionMode.LIVE);
        verifyNoInteractions(paperExecutionService);
    }

    @Test
    void unarmedLiveExitStillRoutesToLiveExecutor() {
        properties.setMode(ExecutionMode.LIVE);
        TradeIntent intent = sellIntent();
        TradeExecutionResult liveResult = TradeExecutionResult.accepted(
                ExecutionMode.LIVE,
                5317L,
                6363L,
                TradeStatus.EXIT_PENDING,
                TradeOrderStatus.SUBMITTED,
                "live exit submitted"
        );
        when(liveExecutionService.execute(intent, ExecutionMode.LIVE)).thenReturn(liveResult);

        TradeExecutionResult result = router.route(intent);

        assertThat(result).isSameAs(liveResult);
        verify(liveExecutionService).execute(intent, ExecutionMode.LIVE);
        verifyNoInteractions(paperExecutionService, liveArmService);
    }

    @Test
    void missingModeFailsInsteadOfDefaultingToPaper() {
        properties.setMode(null);

        assertThatThrownBy(() -> router.route(sellIntent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voktrader.trading.mode");
        verifyNoInteractions(paperExecutionService, liveExecutionService, liveArmService);
    }

    private LiveArmService.LiveArmStatus unarmedStatus() {
        return new LiveArmService.LiveArmStatus(
                false,
                null,
                null,
                null,
                true,
                true,
                true,
                false,
                List.of(),
                List.of("live arm is not active")
        );
    }

    private LiveArmService.LiveArmStatus armedStatus() {
        return new LiveArmService.LiveArmStatus(
                true,
                Instant.parse("2026-07-24T06:00:00Z"),
                Instant.parse("2026-07-24T06:15:00Z"),
                "0xexpected",
                true,
                true,
                true,
                true,
                List.of(),
                List.of()
        );
    }

    private TradeIntent buyIntent() {
        return TradeIntent.buy(
                67L,
                market(),
                price("down", "Down", "0.56", "0.57"),
                new BigDecimal("2.85"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                true,
                new BigDecimal("0.57"),
                "MK_GTD_EDGE_A",
                "entry",
                "strategy-v2 entry"
        );
    }

    private TradeIntent sellIntent() {
        return TradeIntent.sell(
                67L,
                market(),
                price("down", "Down", "0.56", "0.57"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.56"),
                "MK_GTD_EDGE_A",
                "net-take-profit",
                "strategy-v2 exit rule=net-take-profit outcome=Down"
        );
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
