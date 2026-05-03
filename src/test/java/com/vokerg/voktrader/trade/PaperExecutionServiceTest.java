package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import com.vokerg.voktrader.pricing.OutcomePrice;
import com.vokerg.voktrader.telemetry.TradingEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
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
    private final PaperExecutionService service = new PaperExecutionService(
            properties,
            riskCheckService,
            new PaperFeeCalculator(),
            tradeRepository,
            tradeOrderRepository,
            tradeFillRepository,
            riskCheckRepository,
            eventRepository,
            mock(TradingEventLogger.class)
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
                eq("PAPER:default:market-id:up:cost-aware-momentum-paper:BUY")
        )).thenReturn(blocked);

        TradeExecutionResult result = service.execute(TradeIntent.buy(
                market(),
                price("up", "Up", "0.59", "0.61"),
                new BigDecimal("1.00"),
                "cost-aware-momentum-paper",
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
                "cost-aware-momentum-paper",
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
                "cost-aware-momentum-paper",
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
                "cost-aware-momentum-paper",
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
                "cost-aware-momentum-paper",
                "market-id",
                "up",
                TradeStatus.OPEN
        )).thenReturn(Optional.empty());

        TradeExecutionResult result = service.execute(TradeIntent.sell(
                market,
                price("up", "Up", "0.60", "0.62"),
                new BigDecimal("2.00000000"),
                "cost-aware-momentum-paper",
                "cost-aware-momentum",
                "exit"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).isEqualTo("no open trade to close");
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
