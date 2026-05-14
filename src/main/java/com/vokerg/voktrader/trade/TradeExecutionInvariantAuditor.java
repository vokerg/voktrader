package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionInvariantAuditor {
    private final TradeOrderRepository tradeOrderRepository;

    public List<Long> auditLiveEntryPaperExitViolations() {
        List<Long> tradeIds = tradeOrderRepository.findTradeIdsWithLiveEntryAndPaperExit();
        if (!tradeIds.isEmpty()) {
            log.error(
                    "CRITICAL TRADE EXECUTION INVARIANT VIOLATION: live-backed entries have PAPER_SIM exits tradeIds={}",
                    tradeIds
            );
        }
        return tradeIds;
    }
}
