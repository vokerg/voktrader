package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.HistoricalTickCoverageEntity;
import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.HistoricalTickCoverageRepository;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotLevelRepository;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class HistoricalTickCoverageService {
    private static final String MISSING_EVIDENCE_REASON =
            "retained depth snapshots do not contain an authoritative tick_size observation before this interval; "
                    + "price levels must not be used to infer an exchange tick";

    private final MarketDepthSnapshotLevelRepository depthSnapshotRepository;
    private final TickSizeMetadataRepository tickMetadataRepository;
    private final HistoricalTickCoverageRepository coverageRepository;

    public HistoricalTickCoverageService(
            MarketDepthSnapshotLevelRepository depthSnapshotRepository,
            TickSizeMetadataRepository tickMetadataRepository,
            HistoricalTickCoverageRepository coverageRepository
    ) {
        this.depthSnapshotRepository = depthSnapshotRepository;
        this.tickMetadataRepository = tickMetadataRepository;
        this.coverageRepository = coverageRepository;
    }

    @Transactional
    public InventoryResult inventoryDepthSnapshotCoverage() {
        List<HistoricalTickCoverageEntity> blockers = new ArrayList<>();
        int alreadyRecorded = 0;
        int coveredByTimeline = 0;

        for (MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection interval
                : depthSnapshotRepository.inventoryHistoricalTickIntervals()) {
            if (!valid(interval)) {
                continue;
            }

            Optional<TickSizeMetadataEntity> earliestMetadata = tickMetadataRepository
                    .findFirstByTokenIdOrderByEffectiveAtAscIdAsc(interval.getTokenId());
            if (earliestMetadata.isPresent()
                    && !earliestMetadata.get().getEffectiveAt().isAfter(interval.getIntervalStartAt())) {
                coveredByTimeline++;
                continue;
            }

            Instant blockerEnd = earliestMetadata
                    .map(TickSizeMetadataEntity::getEffectiveAt)
                    .filter(firstEvidence -> firstEvidence.isBefore(interval.getIntervalEndAt()))
                    .orElse(interval.getIntervalEndAt());

            boolean exists = coverageRepository
                    .existsByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatus(
                            HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                            interval.getMarketId(),
                            interval.getTokenId(),
                            interval.getIntervalStartAt(),
                            blockerEnd,
                            HistoricalTickCoverageStatus.BLOCKED
                    );
            if (exists) {
                alreadyRecorded++;
                continue;
            }

            blockers.add(HistoricalTickCoverageEntity.blocked(
                    HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                    interval.getMarketId(),
                    interval.getTokenId(),
                    interval.getIntervalStartAt(),
                    blockerEnd,
                    MISSING_EVIDENCE_REASON,
                    Instant.now()
            ));
        }

        if (!blockers.isEmpty()) {
            coverageRepository.saveAll(blockers);
        }
        return new InventoryResult(blockers.size(), alreadyRecorded, coveredByTimeline);
    }

    private boolean valid(MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection interval) {
        return interval != null
                && interval.getTokenId() != null
                && !interval.getTokenId().isBlank()
                && interval.getIntervalStartAt() != null
                && interval.getIntervalEndAt() != null
                && !interval.getIntervalEndAt().isBefore(interval.getIntervalStartAt());
    }

    public record InventoryResult(
            int blockersCreated,
            int blockersAlreadyRecorded,
            int intervalsCoveredByTickTimeline
    ) {
    }
}
