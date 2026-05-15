package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeStatus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

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
    private final ExecutionRouter router = new ExecutionRouter(
            properties,
            paperExecutionService,
            liveExecutionService
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
        verifyNoInteractions(liveExecutionService);
    }

    @Test
    void liveModeRoutesEverythingToLiveExecutor() {
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
        verifyNoInteractions(paperExecutionService);
    }

    @Test
    void missingModeFailsInsteadOfDefaultingToPaper() {
        properties.setMode(null);

        assertThatThrownBy(() -> router.route(sellIntent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voktrader.trading.mode");
        verifyNoInteractions(paperExecutionService, liveExecutionService);
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
