package com.vokerg.voktrader.marketdata.persistence;

import com.vokerg.voktrader.marketdata.HistoricalTickCoverageStatus;
import com.vokerg.voktrader.marketdata.HistoricalTickDatasetType;
import com.vokerg.voktrader.marketdata.model.HistoricalTickCoverageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HistoricalTickCoverageRepository extends JpaRepository<HistoricalTickCoverageEntity, Long> {
    boolean existsByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatus(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt,
            HistoricalTickCoverageStatus coverageStatus
    );

    Optional<HistoricalTickCoverageEntity>
    findFirstByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatusOrderByRecordedAtDescIdDesc(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt,
            HistoricalTickCoverageStatus coverageStatus
    );

    List<HistoricalTickCoverageEntity>
    findByTokenIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt
    );

    List<HistoricalTickCoverageEntity>
    findByDatasetTypeAndMarketIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            Instant intervalStartAt,
            Instant intervalEndAt
    );
}
