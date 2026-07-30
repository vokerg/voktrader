package com.vokerg.voktrader.marketdata.persistence;

import com.vokerg.voktrader.marketdata.HistoricalTickCoverageStatus;
import com.vokerg.voktrader.marketdata.HistoricalTickDatasetType;
import com.vokerg.voktrader.marketdata.model.HistoricalTickCoverageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface HistoricalTickCoverageRepository extends JpaRepository<HistoricalTickCoverageEntity, Long> {
    boolean existsByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatus(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt,
            HistoricalTickCoverageStatus coverageStatus
    );
}
