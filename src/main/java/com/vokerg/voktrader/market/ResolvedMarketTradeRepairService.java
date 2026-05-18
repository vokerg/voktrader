package com.vokerg.voktrader.market;

import com.vokerg.voktrader.trade.TradeSettlementService;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "voktrader.resolution.repair", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ResolvedMarketTradeRepairService {
    private final MarketRepository marketRepository;
    private final TradeRepository tradeRepository;
    private final TradeSettlementService tradeSettlementService;

    @Scheduled(
            fixedDelayString = "${voktrader.resolution.repair.poll-ms:60000}",
            initialDelayString = "${voktrader.resolution.repair.initial-delay-ms:60000}"
    )
    public void repairResolvedMarketsWithOpenTrades() {
        int repaired = repairOnce();
        if (repaired > 0) {
            log.info("Repaired settlement drift for {} resolved markets with OPEN trades", repaired);
        }
    }

    public int repairOnce() {
        List<String> openMarketIds = tradeRepository.findDistinctMarketIdByStatus(TradeStatus.OPEN);
        if (openMarketIds.isEmpty()) {
            return 0;
        }
        List<MarketEntity> resolvedMarkets = marketRepository
                .findResolvedWithWinningOutcomeByPolymarketMarketIdIn(openMarketIds);
        resolvedMarkets.forEach(market -> {
            log.warn("REPAIR MARKET RESOLUTION settlement drift: marketId={} winningOutcome={}",
                    market.getPolymarketMarketId(), market.getWinningOutcome());
            tradeSettlementService.settleOpenTradesForResolvedMarket(
                    market.getPolymarketMarketId(),
                    market.getWinningOutcome()
            );
        });
        return resolvedMarkets.size();
    }
}
