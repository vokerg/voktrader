package com.vokerg.voktrader.market;

import com.vokerg.voktrader.trade.TradeSettlementService;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolvedMarketTradeRepairServiceTest {
    private final MarketRepository marketRepository = mock(MarketRepository.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TradeSettlementService tradeSettlementService = mock(TradeSettlementService.class);
    private final ResolvedMarketTradeRepairService service = new ResolvedMarketTradeRepairService(
            marketRepository,
            tradeRepository,
            tradeSettlementService
    );

    @Test
    void repairJobSettlesResolvedMarketsWithOpenTrades() {
        MarketEntity market = new MarketEntity();
        market.setPolymarketMarketId("market-1");
        market.setResolved(true);
        market.setResolutionStatus(MarketResolutionStatus.RESOLVED);
        market.setWinningOutcome("Yes");

        when(tradeRepository.findDistinctMarketIdByStatus(TradeStatus.OPEN)).thenReturn(List.of("market-1"));
        when(marketRepository.findResolvedWithWinningOutcomeByPolymarketMarketIdIn(List.of("market-1")))
                .thenReturn(List.of(market));

        int repaired = service.repairOnce();

        assertThat(repaired).isEqualTo(1);
        verify(tradeSettlementService).settleOpenTradesForResolvedMarket("market-1", "Yes");
    }
}
