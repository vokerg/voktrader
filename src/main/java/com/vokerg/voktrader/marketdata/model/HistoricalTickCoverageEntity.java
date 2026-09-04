package com.vokerg.voktrader.marketdata.model;

import com.vokerg.voktrader.marketdata.HistoricalTickCoverageStatus;
import com.vokerg.voktrader.marketdata.HistoricalTickDatasetType;
import com.vokerg.voktrader.marketdata.HistoricalTickEvidenceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "historical_tick_coverage",
        indexes = {
                @Index(name = "idx_historical_tick_coverage_token_interval", columnList = "token_id,interval_start_at,interval_end_at"),
                @Index(name = "idx_historical_tick_coverage_status", columnList = "coverage_status,dataset_type")
        }
)
public class HistoricalTickCoverageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_type", nullable = false, length = 32, updatable = false)
    private HistoricalTickDatasetType datasetType;

    @Column(name = "market_id", updatable = false)
    private Long marketId;

    @Column(name = "token_id", length = 128, updatable = false)
    private String tokenId;

    @Column(name = "interval_start_at", nullable = false, updatable = false)
    private Instant intervalStartAt;

    @Column(name = "interval_end_at", nullable = false, updatable = false)
    private Instant intervalEndAt;

    @Column(name = "tick_size", precision = 19, scale = 8, updatable = false)
    private BigDecimal tickSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_status", nullable = false, length = 16, updatable = false)
    private HistoricalTickCoverageStatus coverageStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_source", nullable = false, length = 40, updatable = false)
    private HistoricalTickEvidenceSource evidenceSource;

    @Column(name = "source_reference", columnDefinition = "TEXT", updatable = false)
    private String sourceReference;

    @Column(name = "blocker_reason", columnDefinition = "TEXT", updatable = false)
    private String blockerReason;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected HistoricalTickCoverageEntity() {
    }

    public static HistoricalTickCoverageEntity blocked(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt,
            String blockerReason,
            Instant recordedAt
    ) {
        requireDataset(datasetType);
        requireInterval(intervalStartAt, intervalEndAt);
        if (blockerReason == null || blockerReason.isBlank()) {
            throw new IllegalArgumentException("historical tick blocker reason is required");
        }
        HistoricalTickCoverageEntity entity = new HistoricalTickCoverageEntity();
        entity.datasetType = datasetType;
        entity.marketId = marketId;
        entity.tokenId = tokenId;
        entity.intervalStartAt = intervalStartAt;
        entity.intervalEndAt = intervalEndAt;
        entity.coverageStatus = HistoricalTickCoverageStatus.BLOCKED;
        entity.evidenceSource = HistoricalTickEvidenceSource.UNRESOLVED;
        entity.blockerReason = blockerReason;
        entity.recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        return entity;
    }

    public static HistoricalTickCoverageEntity resolved(
            HistoricalTickDatasetType datasetType,
            Long marketId,
            String tokenId,
            Instant intervalStartAt,
            Instant intervalEndAt,
            BigDecimal tickSize,
            HistoricalTickEvidenceSource evidenceSource,
            String sourceReference,
            Instant recordedAt
    ) {
        requireDataset(datasetType);
        requireInterval(intervalStartAt, intervalEndAt);
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("resolved historical tick tokenId is required");
        }
        if (tickSize == null || tickSize.signum() <= 0) {
            throw new IllegalArgumentException("resolved historical tick size must be positive");
        }
        if (evidenceSource == null || evidenceSource == HistoricalTickEvidenceSource.UNRESOLVED) {
            throw new IllegalArgumentException("resolved historical tick evidence source is required");
        }
        if (sourceReference == null || sourceReference.isBlank()) {
            throw new IllegalArgumentException("resolved historical tick source reference is required");
        }
        HistoricalTickCoverageEntity entity = new HistoricalTickCoverageEntity();
        entity.datasetType = datasetType;
        entity.marketId = marketId;
        entity.tokenId = tokenId;
        entity.intervalStartAt = intervalStartAt;
        entity.intervalEndAt = intervalEndAt;
        entity.tickSize = tickSize;
        entity.coverageStatus = HistoricalTickCoverageStatus.RESOLVED;
        entity.evidenceSource = evidenceSource;
        entity.sourceReference = sourceReference;
        entity.recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        return entity;
    }

    private static void requireDataset(HistoricalTickDatasetType datasetType) {
        if (datasetType == null) {
            throw new IllegalArgumentException("historical tick dataset type is required");
        }
    }

    private static void requireInterval(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("historical tick coverage interval is required");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("historical tick coverage interval end precedes start");
        }
    }

    public Long getId() { return id; }
    public HistoricalTickDatasetType getDatasetType() { return datasetType; }
    public Long getMarketId() { return marketId; }
    public String getTokenId() { return tokenId; }
    public Instant getIntervalStartAt() { return intervalStartAt; }
    public Instant getIntervalEndAt() { return intervalEndAt; }
    public BigDecimal getTickSize() { return tickSize; }
    public HistoricalTickCoverageStatus getCoverageStatus() { return coverageStatus; }
    public HistoricalTickEvidenceSource getEvidenceSource() { return evidenceSource; }
    public String getSourceReference() { return sourceReference; }
    public String getBlockerReason() { return blockerReason; }
    public Instant getRecordedAt() { return recordedAt; }
}
