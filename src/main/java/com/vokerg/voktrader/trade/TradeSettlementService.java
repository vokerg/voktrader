package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeEventEntity;
import com.vokerg.voktrader.trade.model.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSettlementService {
    private final TradeRepository tradeRepository;
    private final TradeEventRepository eventRepository;

    @Transactional
    public void settleOpenTradesForResolvedMarket(String marketId, String winningOutcome) {
        if (marketId == null || marketId.isBlank() || winningOutcome == null || winningOutcome.isBlank()) {
            return;
        }
        List<TradeEntity> openTrades = tradeRepository.findByMarketIdAndStatus(marketId, TradeStatus.OPEN);
        if (openTrades.isEmpty()) {
            log.info("No OPEN trades to settle for resolved marketId={}", marketId);
            return;
        }
        Instant now = Instant.now();
        for (TradeEntity trade : openTrades) {
            trade.resolve(winningOutcome, now);
            tradeRepository.save(trade);
            eventRepository.save(TradeEventEntity.of(
                    trade.getId(), null, null, "MARKET_RESOLVED",
                    "trade settled from market resolution winningOutcome=" + winningOutcome,
                    null));
            log.info(
                    "TRADE SETTLED: tradeId={} marketId={} outcome={} winningOutcome={} status={} entryPrice={} amountUsd={} shares={} finalPnl={}",
                    trade.getId(), trade.getMarketId(), trade.getOutcome(), trade.getWinningOutcome(), trade.getStatus(),
                    trade.getEntryAvgPrice(), trade.getEntryFilledUsd(), trade.getEntryFilledShares(), trade.getFinalPnlUsd());
        }
    }
}
