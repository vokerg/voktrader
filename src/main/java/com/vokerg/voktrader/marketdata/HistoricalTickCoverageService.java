package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.HistoricalTickCoverageEntity;
import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.HistoricalTickCoverageRepository;
import com.vokerg.voktrader.marketdata.persistence.MarketDepthSnapshotLevelRepository;
import com.vokerg.voktrader.marketdata.persistence.PriceSnapshotRepository;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class HistoricalTickCoverageService {
    private static final String MISSING_DEPTH_EVIDENCE_REASON =
            "retained depth snapshots do not contain an authoritative tick_size observation before this interval; "
                    + "price levels must not be used to infer an exchange tick";
    private static final String MISSING_PRICE_EVIDENCE_REASON =
            "retained price snapshots contain prices and timestamps but no outcome token IDs or protocol tick_size evidence; "
                    + "timestamped market-to-token and tick evidence is required before replay";

    private final MarketDepthSnapshotLevelRepository depthSnapshotRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final TickSizeMetadataRepository tickMetadataRepository;
    private final HistoricalTickCoverageRepository coverageRepository;

    @Autowired
    public HistoricalTickCoverageService(
            MarketDepthSnapshotLevelRepository depthSnapshotRepository,
            PriceSnapshotRepository priceSnapshotRepository,
            TickSizeMetadataRepository tickMetadataRepository,
            HistoricalTickCoverageRepository coverageRepository
    ) {
        this.depthSnapshotRepository = depthSnapshotRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.tickMetadataRepository = tickMetadataRepository;
        this.coverageRepository = coverageRepository;
    }

    HistoricalTickCoverageService(
            MarketDepthSnapshotLevelRepository depthSnapshotRepository,
            TickSizeMetadataRepository tickMetadataRepository,
            HistoricalTickCoverageRepository coverageRepository
    ) {
        this(depthSnapshotRepository, null, tickMetadataRepository, coverageRepository);
    }

    @Transactional
    public InventoryResult inventoryDepthSnapshotCoverage() {
        List<HistoricalTickCoverageEntity> blockers = new ArrayList<>();
        List<HistoricalTickCoverageEntity> resolved = new ArrayList<>();
        int blockersAlreadyRecorded = 0;
        int resolvedAlreadyRecorded = 0;
        int intervalsCoveredByTimeline = 0;

        for (MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection interval
                : depthSnapshotRepository.inventoryHistoricalTickIntervals()) {
            if (!validDepth(interval)) {
                continue;
            }

            Optional<TickSizeMetadataEntity> earliestMetadata = tickMetadataRepository
                    .findFirstByTokenIdOrderByEffectiveAtAscIdAsc(interval.getTokenId());
            if (earliestMetadata.isEmpty()) {
                if (addBlockerIfMissing(
                        blockers,
                        HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                        interval.getMarketId(),
                        interval.getTokenId(),
                        interval.getIntervalStartAt(),
                        interval.getIntervalEndAt(),
                        MISSING_DEPTH_EVIDENCE_REASON
                )) {
                    continue;
                }
                blockersAlreadyRecorded++;
                continue;
            }

            TickSizeMetadataEntity firstEvidence = earliestMetadata.get();
            Instant firstEvidenceAt = firstEvidence.getEffectiveAt();
            if (firstEvidenceAt.isAfter(interval.getIntervalStartAt())) {
                Instant blockerEnd = firstEvidenceAt.isBefore(interval.getIntervalEndAt())
                        ? firstEvidenceAt
                        : interval.getIntervalEndAt();
                if (!addBlockerIfMissing(
                        blockers,
                        HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                        interval.getMarketId(),
                        interval.getTokenId(),
                        interval.getIntervalStartAt(),
                        blockerEnd,
                        MISSING_DEPTH_EVIDENCE_REASON
                )) {
                    blockersAlreadyRecorded++;
                }
            }

            if (!firstEvidenceAt.isAfter(interval.getIntervalEndAt())) {
                Instant resolvedStart = firstEvidenceAt.isAfter(interval.getIntervalStartAt())
                        ? firstEvidenceAt
                        : interval.getIntervalStartAt();
                if (addResolvedIfMissing(
                        resolved,
                        HistoricalTickDatasetType.MARKET_DEPTH_SNAPSHOT,
                        interval.getMarketId(),
                        interval.getTokenId(),
                        resolvedStart,
                        interval.getIntervalEndAt(),
                        firstEvidence.getTickSize(),
                        HistoricalTickEvidenceSource.TICK_METADATA_TIMELINE,
                        timelineReference(firstEvidence)
                )) {
                    intervalsCoveredByTimeline++;
                } else {
                    resolvedAlreadyRecorded++;
                    intervalsCoveredByTimeline++;
                }
            }
        }

        saveCoverage(blockers, resolved);
        return new InventoryResult(
                blockers.size(),
                blockersAlreadyRecorded,
                intervalsCoveredByTimeline,
                resolved.size(),
                resolvedAlreadyRecorded
        );
    }

    @Transactional
    public PriceSnapshotInventoryResult inventoryPriceSnapshotCoverage() {
        if (priceSnapshotRepository == null) {
            throw new IllegalStateException("price snapshot repository is unavailable");
        }
        List<HistoricalTickCoverageEntity> blockers = new ArrayList<>();
        int alreadyRecorded = 0;
        int invalidIntervals = 0;

        for (PriceSnapshotRepository.HistoricalTickIntervalProjection interval
                : priceSnapshotRepository.inventoryHistoricalTickIntervals()) {
            if (interval == null
                    || interval.getIntervalStartAt() == null
                    || interval.getIntervalEndAt() == null
                    || interval.getIntervalEndAt().isBefore(interval.getIntervalStartAt())) {
                invalidIntervals++;
                continue;
            }
            Long marketId = interval.getMarketId() != null ? interval.getMarketId() : interval.getMarketEntityId();
            if (!addBlockerIfMissing(
                    blockers,
                    HistoricalTickDatasetType.PRICE_SNAPSHOT,
                    marketId,
                    null,
                    interval.getIntervalStartAt(),
                    interval.getIntervalEndAt(),
                    MISSING_PRICE_EVIDENCE_REASON
            )) {
                alreadyRecorded++;
            }
        }

        if (!blockers.isEmpty()) {
            coverageRepository.saveAll(blockers);
        }
        return new PriceSnapshotInventoryResult(blockers.size(), alreadyRecorded, invalidIntervals);
    }

    @Transactional
    public ImportResult importVerifiedEvidence(VerifiedTickEvidence evidence) {
        VerifiedTickEvidence required = requireEvidence(evidence);
        BigDecimal parsedTick = TickMath.parseTick(required.tickSize().toPlainString());
        TickSizeSource tickSource = switch (required.evidenceSource()) {
            case DOCUMENTED_REST_BOOK -> TickSizeSource.REST_BOOK;
            case DOCUMENTED_WEBSOCKET_EVENT -> TickSizeSource.MARKET_WEBSOCKET;
            default -> throw new IllegalArgumentException(
                    "only documented REST-book or WebSocket evidence may be imported"
            );
        };

        Optional<TickSizeMetadataEntity> existingAtStart = tickMetadataRepository
                .findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
                        required.tokenId(),
                        required.intervalStartAt()
                );
        boolean metadataCreated = existingAtStart.isEmpty()
                || existingAtStart.get().getTickSize().compareTo(parsedTick) != 0;
        if (metadataCreated) {
            tickMetadataRepository.save(TickSizeMetadataEntity.observed(
                    required.tokenId(),
                    required.protocolMarketId(),
                    parsedTick,
                    required.intervalStartAt(),
                    required.observedAt() == null ? Instant.now() : required.observedAt(),
                    tickSource
            ));
        }

        Optional<HistoricalTickCoverageEntity> existingCoverage = coverageRepository
                .findFirstByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatusOrderByRecordedAtDescIdDesc(
                        required.datasetType(),
                        required.datasetMarketId(),
                        required.tokenId(),
                        required.intervalStartAt(),
                        required.intervalEndAt(),
                        HistoricalTickCoverageStatus.RESOLVED
                );
        if (existingCoverage.isPresent()) {
            HistoricalTickCoverageEntity existing = existingCoverage.get();
            if (existing.getTickSize().compareTo(parsedTick) != 0
                    || existing.getEvidenceSource() != required.evidenceSource()
                    || !existing.getSourceReference().equals(required.sourceReference())) {
                throw new IllegalStateException("conflicting resolved historical tick evidence already exists");
            }
            return new ImportResult(metadataCreated, false);
        }

        coverageRepository.save(HistoricalTickCoverageEntity.resolved(
                required.datasetType(),
                required.datasetMarketId(),
                required.tokenId(),
                required.intervalStartAt(),
                required.intervalEndAt(),
                parsedTick,
                required.evidenceSource(),
                required.sourceReference(),
                Instant.now()
        ));
        return new ImportResult(metadataCreated, true);
    }

    @Transactional(readOnly = true)
    public void assertTokenReplayReady(String tokenId, Instant effectiveAt) {
        String requiredTokenId = requireTokenId(tokenId);
        Instant requiredEffectiveAt = requireEffectiveAt(effectiveAt);
        List<HistoricalTickCoverageEntity> active = coverageRepository
                .findByTokenIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                        requiredTokenId,
                        requiredEffectiveAt,
                        requiredEffectiveAt
                );
        Set<HistoricalTickDatasetType> inspectedDatasets = EnumSet.noneOf(HistoricalTickDatasetType.class);
        for (HistoricalTickCoverageEntity coverage : active) {
            if (inspectedDatasets.add(coverage.getDatasetType())
                    && coverage.getCoverageStatus() == HistoricalTickCoverageStatus.BLOCKED) {
                throw blocked(coverage, requiredEffectiveAt);
            }
        }
        requireTimelineMetadata(requiredTokenId, requiredEffectiveAt);
    }

    @Transactional(readOnly = true)
    public void assertReplayReady(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant effectiveAt
    ) {
        if (datasetType == null) {
            throw new IllegalArgumentException("historical replay dataset type is required");
        }
        String requiredTokenId = requireTokenId(tokenId);
        Instant requiredEffectiveAt = requireEffectiveAt(effectiveAt);
        List<HistoricalTickCoverageEntity> active = coverageRepository
                .findByDatasetTypeAndMarketIdAndIntervalStartAtLessThanEqualAndIntervalEndAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
                        datasetType,
                        marketId,
                        requiredEffectiveAt,
                        requiredEffectiveAt
                );

        HistoricalTickCoverageEntity latestApplicable = active.stream()
                .filter(coverage -> coverage.getTokenId() == null
                        || requiredTokenId.equals(coverage.getTokenId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "historical tick coverage is missing for dataset=" + datasetType
                                + " marketId=" + marketId
                                + " tokenId=" + requiredTokenId
                                + " at " + requiredEffectiveAt
                ));
        if (latestApplicable.getCoverageStatus() == HistoricalTickCoverageStatus.BLOCKED) {
            throw blocked(latestApplicable, requiredEffectiveAt);
        }
        requireTimelineMetadata(requiredTokenId, requiredEffectiveAt);
    }

    private TickSizeMetadataEntity requireTimelineMetadata(String tokenId, Instant effectiveAt) {
        return tickMetadataRepository
                .findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(tokenId, effectiveAt)
                .orElseThrow(() -> new IllegalStateException(
                        "historical tick metadata and coverage are unavailable for tokenId=" + tokenId
                                + " at " + effectiveAt
                ));
    }

    private IllegalStateException blocked(HistoricalTickCoverageEntity coverage, Instant effectiveAt) {
        return new IllegalStateException(
                "historical replay is blocked for dataset=" + coverage.getDatasetType()
                        + " marketId=" + coverage.getMarketId()
                        + " tokenId=" + coverage.getTokenId()
                        + " at " + effectiveAt
                        + ": " + coverage.getBlockerReason()
        );
    }

    private boolean addBlockerIfMissing(
            List<HistoricalTickCoverageEntity> target,
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant start,
            Instant end,
            String reason
    ) {
        boolean exists = coverageRepository
                .existsByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatus(
                        datasetType,
                        marketId,
                        tokenId,
                        start,
                        end,
                        HistoricalTickCoverageStatus.BLOCKED
                );
        if (exists) {
            return false;
        }
        target.add(HistoricalTickCoverageEntity.blocked(
                datasetType,
                marketId,
                tokenId,
                start,
                end,
                reason,
                Instant.now()
        ));
        return true;
    }

    private boolean addResolvedIfMissing(
            List<HistoricalTickCoverageEntity> target,
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant start,
            Instant end,
            BigDecimal tickSize,
            HistoricalTickEvidenceSource source,
            String sourceReference
    ) {
        Optional<HistoricalTickCoverageEntity> existing = coverageRepository
                .findFirstByDatasetTypeAndMarketIdAndTokenIdAndIntervalStartAtAndIntervalEndAtAndCoverageStatusOrderByRecordedAtDescIdDesc(
                        datasetType,
                        marketId,
                        tokenId,
                        start,
                        end,
                        HistoricalTickCoverageStatus.RESOLVED
                );
        if (existing.isPresent()) {
            HistoricalTickCoverageEntity resolved = existing.get();
            if (resolved.getTickSize().compareTo(tickSize) != 0
                    || resolved.getEvidenceSource() != source
                    || !resolved.getSourceReference().equals(sourceReference)) {
                throw new IllegalStateException("conflicting historical tick coverage already exists");
            }
            return false;
        }
        target.add(HistoricalTickCoverageEntity.resolved(
                datasetType,
                marketId,
                tokenId,
                start,
                end,
                tickSize,
                source,
                sourceReference,
                Instant.now()
        ));
        return true;
    }

    private void saveCoverage(
            List<HistoricalTickCoverageEntity> blockers,
            List<HistoricalTickCoverageEntity> resolved
    ) {
        if (!blockers.isEmpty()) {
            coverageRepository.saveAll(blockers);
        }
        if (!resolved.isEmpty()) {
            coverageRepository.saveAll(resolved);
        }
    }

    private VerifiedTickEvidence requireEvidence(VerifiedTickEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("verified historical tick evidence is required");
        }
        if (evidence.datasetType() == null) {
            throw new IllegalArgumentException("verified evidence dataset type is required");
        }
        requireTokenId(evidence.tokenId());
        if (evidence.intervalStartAt() == null || evidence.intervalEndAt() == null
                || evidence.intervalEndAt().isBefore(evidence.intervalStartAt())) {
            throw new IllegalArgumentException("verified evidence interval is invalid");
        }
        if (evidence.tickSize() == null) {
            throw new IllegalArgumentException("verified evidence tick size is required");
        }
        if (evidence.evidenceSource() == null) {
            throw new IllegalArgumentException("verified evidence source is required");
        }
        if (evidence.sourceReference() == null || evidence.sourceReference().isBlank()) {
            throw new IllegalArgumentException("verified evidence source reference is required");
        }
        return evidence;
    }

    private String timelineReference(TickSizeMetadataEntity metadata) {
        return "tick_size_metadata:" + metadata.getTokenId() + "@" + metadata.getEffectiveAt();
    }

    private boolean validDepth(MarketDepthSnapshotLevelRepository.HistoricalTickIntervalProjection interval) {
        return interval != null
                && interval.getTokenId() != null
                && !interval.getTokenId().isBlank()
                && interval.getIntervalStartAt() != null
                && interval.getIntervalEndAt() != null
                && !interval.getIntervalEndAt().isBefore(interval.getIntervalStartAt());
    }

    private String requireTokenId(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("historical tick tokenId is required");
        }
        return tokenId;
    }

    private Instant requireEffectiveAt(Instant effectiveAt) {
        if (effectiveAt == null) {
            throw new IllegalArgumentException("historical replay effectiveAt is required");
        }
        return effectiveAt;
    }

    public record InventoryResult(
            int blockersCreated,
            int blockersAlreadyRecorded,
            int intervalsCoveredByTickTimeline,
            int resolvedIntervalsCreated,
            int resolvedIntervalsAlreadyRecorded
    ) {
    }

    public record PriceSnapshotInventoryResult(
            int blockersCreated,
            int blockersAlreadyRecorded,
            int invalidIntervals
    ) {
    }

    public record VerifiedTickEvidence(
            HistoricalTickDatasetType datasetType,
            Long datasetMarketId,
            String protocolMarketId,
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt,
            BigDecimal tickSize,
            HistoricalTickEvidenceSource evidenceSource,
            String sourceReference,
            Instant observedAt
    ) {
    }

    public record ImportResult(boolean metadataCreated, boolean coverageCreated) {
    }
}
