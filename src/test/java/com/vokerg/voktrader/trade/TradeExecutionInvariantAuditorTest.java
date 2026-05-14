package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeExecutionInvariantAuditorTest {
    @Test
    void auditReportsLiveEntryPaperExitInvariantViolations() {
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findTradeIdsWithLiveEntryAndPaperExit()).thenReturn(List.of(5308L));
        TradeExecutionInvariantAuditor auditor = new TradeExecutionInvariantAuditor(repository);

        List<Long> tradeIds = auditor.auditLiveEntryPaperExitViolations();

        assertThat(tradeIds).containsExactly(5308L);
        verify(repository).findTradeIdsWithLiveEntryAndPaperExit();
    }
}
