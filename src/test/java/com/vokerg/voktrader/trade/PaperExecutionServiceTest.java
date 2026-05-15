package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeRiskCheckEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import com.vokerg.voktrader.trade.persistence.TradeRiskCheckRepository;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.marketdata.OutcomePrice;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperExecutionServiceTest {

    private final TradingProperties properties = new TradingProperties();
    private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
    private final TradeFillRepository tradeFillRepository = mock(TradeFillRepository.class);
    private final TradeRiskCheckRepository riskCheckRepository = mock(TradeRiskCheckRepository.class);
    private final TradeEventRepository eventRepository = mock(TradeEventRepository.class);
    private final TradingEventLogger eventLogger = mock(TradingEventLogger.class);
    private final TradeExecutionSafetyService safetyService = new TradeExecutionSafetyService(
            tradeOrderRepository,
            eventRepository,
            eventLogger,
            new ObjectMapper()
    );
    private final PaperExecutionService service = new PaperExecutionService(
            properties,
            riskCheckService,
            new PaperFeeCalculator(),
            tradeRepository,
            tradeOrderRepository,
            tradeFillRepository,
            riskCheckRepository,
            eventRepository,
            eventLogger,
            safetyService
    );

    @Test
    void buyRejectedByRiskCreatesOnlyRiskAuditRows() {
        RiskAssessment blocked = new RiskAssessment();
        blocked.add(TradeRiskCheckEntity.of(
                null,
                null,
                ExecutionMode.PAPER,
                "MAX_TRADES_PER_MARKET",
                false,
                RiskSeverity.BLOCK,
                1,
                1,
                "maxTradesPerMarket reached"
        ));
        when(riskCheckService.assess(
                any(TradeIntent.class),
                eq(ExecutionMode.PAPER),
                eq(null),
                eq(null),
                eq("PAPER:default:market-id:up:cost-aware-momentum:BUY")
        )).thenReturn(blocked);

        TradeExecutionResult result = service.execute(TradeIntent.buy(
                market(),
                price("up", "Up", "0.59", "0.61"),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.tradeId()).isNull();
        assertThat(result.orderId()).isNull();
        assertThat(result.message()).isEqualTo("maxTradesPerMarket reached");

        verify(riskCheckRepository).saveAll(blocked.checks());
        verify(tradeRepository, org.mockito.Mockito.never()).save(any());
        verify(tradeOrderRepository, org.mockito.Mockito.never()).save(any());
        verify(tradeFillRepository, org.mockito.Mockito.never()).save(any());
        verify(eventRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void sellClosesMatchingOpenTradeAtBid() {
        properties.setPaperFeeRate(BigDecimal.ZERO);
        GammaMarketDto market = market();
        TradeEntity openTrade = TradeEntity.fromIntent(TradeIntent.buy(
                market,
                price("up", "Up", "0.49", "0.50"),
                new BigDecimal("1.00"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "entry"
        ), ExecutionMode.PAPER);
        openTrade.markOpen(
                new BigDecimal("0.50"),
                new BigDecimal("2.00000000"),
                new BigDecimal("1.00"),
                BigDecimal.ZERO,
                Instant.parse("2026-04-30T10:00:00Z")
        );

        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                "cost-aware-momentum",
                "market-id",
                "up",
                TradeStatus.OPEN
        )).thenReturn(Optional.of(openTrade));
        when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeFillRepository.save(any(TradeFillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market,
                price("up", "Up", "0.60", "0.62"),
                new BigDecimal("2.00000000"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "exit"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(openTrade.getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(openTrade.getExitAvgPrice()).isEqualByComparingTo("0.60");
        assertThat(openTrade.getRealizedPnlUsd()).isEqualByComparingTo("0.20000000");

        ArgumentCaptor<TradeOrderEntity> orderCaptor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(tradeOrderRepository, org.mockito.Mockito.times(2)).save(orderCaptor.capture());
        TradeOrderEntity filledOrder = orderCaptor.getAllValues().getLast();
        assertThat(filledOrder.getSide()).isEqualTo(TradeSide.SELL);
        assertThat(filledOrder.getStatus()).isEqualTo(TradeOrderStatus.FILLED);

        ArgumentCaptor<TradeFillEntity> fillCaptor = ArgumentCaptor.forClass(TradeFillEntity.class);
        verify(tradeFillRepository).save(fillCaptor.capture());
        assertThat(fillCaptor.getValue().getSide()).isEqualTo(TradeSide.SELL);
        assertThat(fillCaptor.getValue().getPrice()).isEqualByComparingTo("0.60");
    }

    @Test
    void sellRejectsWhenNoOpenTradeExists() {
        GammaMarketDto market = market();
        when(tradeRepository.findFirstByStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                "cost-aware-momentum",
                "market-id",
                "up",
                TradeStatus.OPEN
        )).thenReturn(Optional.empty());

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market,
                price("up", "Up", "0.60", "0.62"),
                new BigDecimal("2.00000000"),
                "cost-aware-momentum",
                "cost-aware-momentum",
                "exit"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).isEqualTo("no open trade to close");
    }

    @Test
    void sellRejectsLiveBackedTradeBeforeSimulatedClose() {
        GammaMarketDto market = market();
        TradeIntent entryIntent = TradeIntent.buy(
                67L,
                market,
                price("down", "Down", "0.50", "0.51"),
                new BigDecimal("2.55"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.51"),
                "MK_GTD_EDGE_A",
                "mk-gtd-edge-a-entry",
                "entry"
        );
        TradeEntity liveTrade = TradeEntity.fromIntent(entryIntent, ExecutionMode.LIVE);
        ReflectionTestUtils.setField(liveTrade, "id", 5308L);
        liveTrade.markOpen(
                new BigDecimal("0.51"),
                new BigDecimal("5"),
                new BigDecimal("2.55"),
                BigDecimal.ZERO,
                Instant.parse("2026-05-13T18:26:46Z")
        );
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5308L, entryIntent, ExecutionMode.LIVE, TradeVenue.POLYMARKET, "entry-local");
        ReflectionTestUtils.setField(entryOrder, "id", 6355L);
        entryOrder.markSubmitting("entry-local", "{}");
        entryOrder.markFilled("0xec019dfad0d11eedca8c3c47071f4a279dcd74ef548fc7969721f1e7b7a268e7", new BigDecimal("0.51"), new BigDecimal("5"), new BigDecimal("2.55"));

        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_A",
                "market-id",
                "down",
                TradeStatus.OPEN
        )).thenReturn(Optional.of(liveTrade));
        when(tradeOrderRepository.findByTradeId(5308L)).thenReturn(List.of(entryOrder));
        when(eventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                67L,
                market,
                price("down", "Down", "0.40", "0.41"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.40"),
                "MK_GTD_EDGE_A",
                "book-pressure-flips",
                "strategy-v2 exit strategy=MK_GTD_EDGE_A rule=book-pressure-flips outcome=Down"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.tradeId()).isEqualTo(5308L);
        assertThat(result.tradeStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(result.message()).isEqualTo("paper exit blocked for live-backed trade");
        assertThat(liveTrade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(liveTrade.getExitFilledUsd()).isNull();

        verify(tradeOrderRepository, org.mockito.Mockito.never()).save(any());
        verify(tradeFillRepository, org.mockito.Mockito.never()).save(any());
        verify(tradeRepository, org.mockito.Mockito.never()).save(any());

        ArgumentCaptor<TradeEventEntity> eventCaptor = ArgumentCaptor.forClass(TradeEventEntity.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(TradeEventEntity::getEventType)
                .containsExactly("PAPER_EXIT_BLOCKED_LIVE_TRADE")
                .doesNotContain("EXIT_ORDER_CREATED", "EXIT_FILLED", "CLOSED");
    }

    @Test
    void sellRejectsExchangeOrderIdBackedTradeBeforeSimulatedClose() {
        GammaMarketDto market = market();
        TradeIntent entryIntent = TradeIntent.buy(
                67L,
                market,
                price("down", "Down", "0.50", "0.51"),
                new BigDecimal("2.55"),
                new BigDecimal("5"),
                TradeOrderType.GTD,
                true,
                new BigDecimal("0.51"),
                "MK_GTD_EDGE_A",
                "mk-gtd-edge-a-entry",
                "entry"
        );
        TradeEntity trade = TradeEntity.fromIntent(entryIntent, ExecutionMode.PAPER);
        ReflectionTestUtils.setField(trade, "id", 5308L);
        trade.markOpen(
                new BigDecimal("0.51"),
                new BigDecimal("5"),
                new BigDecimal("2.55"),
                BigDecimal.ZERO,
                Instant.parse("2026-05-13T18:26:46Z")
        );
        TradeOrderEntity entryOrder = TradeOrderEntity.fromIntent(5308L, entryIntent, ExecutionMode.PAPER, TradeVenue.PAPER_SIM, "entry-local");
        ReflectionTestUtils.setField(entryOrder, "id", 6355L);
        ReflectionTestUtils.setField(entryOrder, "exchangeOrderId", "0xec019dfad0d11eedca8c3c47071f4a279dcd74ef548fc7969721f1e7b7a268e7");
        ReflectionTestUtils.setField(entryOrder, "remoteOrderId", null);

        when(tradeRepository.findFirstByBotIdAndStrategyIdAndMarketIdAndTokenIdAndStatusOrderByCreatedAtDesc(
                67L,
                "MK_GTD_EDGE_A",
                "market-id",
                "down",
                TradeStatus.OPEN
        )).thenReturn(Optional.of(trade));
        when(tradeOrderRepository.findByTradeId(5308L)).thenReturn(List.of(entryOrder));
        when(eventRepository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                67L,
                market,
                price("down", "Down", "0.40", "0.41"),
                new BigDecimal("5"),
                TradeOrderType.FAK,
                new BigDecimal("0.40"),
                "MK_GTD_EDGE_A",
                "book-pressure-flips",
                "strategy-v2 exit strategy=MK_GTD_EDGE_A rule=book-pressure-flips outcome=Down"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).isEqualTo("paper exit blocked for live-backed trade");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(trade.getExitFilledUsd()).isNull();

        verify(tradeOrderRepository, org.mockito.Mockito.never()).save(any());
        verify(tradeFillRepository, org.mockito.Mockito.never()).save(any());
        verify(tradeRepository, org.mockito.Mockito.never()).save(any());

        ArgumentCaptor<TradeEventEntity> eventCaptor = ArgumentCaptor.forClass(TradeEventEntity.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("PAPER_EXIT_BLOCKED_LIVE_TRADE");
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
