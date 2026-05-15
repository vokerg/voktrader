package com.vokerg.voktrader.trade;
import com.vokerg.voktrader.trade.model.ExecutionMode;

import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeExecutionSafetyServiceTest {
    private static final String REMOTE_ORDER_ID = "0xae1db749b628a6740b4ae97e053271c03331bfcd5cb705c12219f38b3ec30227";

    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeEventRepository tradeEventRepository = mock(TradeEventRepository.class);
    private final TradeExecutionSafetyService service = new TradeExecutionSafetyService(
            tradeOrderRepository,
            tradeEventRepository,
            mock(TradingEventLogger.class),
            new ObjectMapper()
    );

    @Test
    void paperSimExitOrderForLiveBackedTradeFailsAtPersistenceBoundary() {
        TradeIntent entryIntent = entryIntent();
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5314L, entryIntent, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "entry-local");
        entryOrder.markFilled(REMOTE_ORDER_ID, new BigDecimal("0.58"), new BigDecimal("5"), new BigDecimal("2.90"));
        TradeOrderEntity paperExit = TradeOrderEntity.fromIntent(
                5314L,
                exitIntent(),
                ExecutionMode.PAPER,
                TradeVenue.PAPER_SIM,
                "paper-exit-local"
        );
        when(tradeOrderRepository.findByTradeId(5314L)).thenReturn(List.of(entryOrder));

        assertThatThrownBy(() -> service.assertNoPaperExitOrderForLiveBackedTrade(paperExit, "repository.save"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAPER_SIM exit order blocked")
                .hasMessageContaining("tradeId=5314")
                .hasMessageContaining("entryRemoteOrderId=" + REMOTE_ORDER_ID);
    }

    @Test
    void paperCloseForLiveBackedTradeFailsBeforeMarkClosed() {
        TradeEntity trade = TradeEntity.fromIntent(entryIntent(), ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 5314L);
        trade.markOpen(new BigDecimal("0.58"), new BigDecimal("5"), new BigDecimal("2.90"), BigDecimal.ZERO, Instant.parse("2026-05-14T17:06:31.396495Z"));

        assertThatThrownBy(() -> service.assertPaperMayCloseTrade(trade, exitIntent(), "PaperExecutionService.beforeMarkClosed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paper close blocked")
                .hasMessageContaining("tradeId=5314");
    }

    @Test
    void blockedPaperExitEmitsDiagnosticEventWithEntryDetails() {
        TradeEntity trade = TradeEntity.fromIntent(entryIntent(), ExecutionMode.LIVE);
        ReflectionTestUtils.setField(trade, "id", 5314L);
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5314L, entryIntent(), ExecutionMode.LIVE, TradeVenue.POLYMARKET, "entry-local");
        ReflectionTestUtils.setField(entryOrder, "id", 6362L);
        entryOrder.markFilled(REMOTE_ORDER_ID, new BigDecimal("0.58"), new BigDecimal("5"), new BigDecimal("2.90"));
        when(tradeOrderRepository.findByTradeId(5314L)).thenReturn(List.of(entryOrder));
        when(tradeEventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TradeExecutionResult> result = service.rejectPaperExitIfLiveBacked(
                trade,
                exitIntent(),
                ExecutionMode.PAPER,
                "PaperExecutionService.executeSell"
        );

        assertThat(result).isPresent();
        assertThat(result.get().accepted()).isFalse();
        assertThat(result.get().message()).isEqualTo("paper exit blocked for live-backed trade");
    }

    private TradeIntent entryIntent() {
        return TradeIntent.buy(
                67L,
                market(),
                price("down-token-5314", "Down", "0.57", "0.58"),
                new BigDecimal("2.90"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.58"),
                "MK_GTD_EDGE_A",
                "mk-gtd-edge-a-entry",
                "entry"
        );
    }

    private TradeIntent exitIntent() {
        return TradeIntent.sell(
                67L,
                market(),
                price("down-token-5314", "Down", "0.63", "0.64"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.63"),
                "MK_GTD_EDGE_A",
                "net-take-profit",
                "strategy-v2 exit rule=net-take-profit outcome=Down"
        );
    }

    private GammaMarketDto market() {
        return new GammaMarketDto(
                "2251671",
                "BTC Up or Down?",
                "condition-id",
                "btc-updown",
                Instant.parse("2026-05-14T17:10:00Z"),
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
                Instant.parse("2026-05-14T17:06:35Z")
        );
    }
}
