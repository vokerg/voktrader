package com.vokerg.voktrader.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "voktrader.historical-tick.evidence-file")
public class HistoricalTickEvidenceImportRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(HistoricalTickEvidenceImportRunner.class);

    private final HistoricalTickCoverageService coverageService;
    private final ObjectMapper objectMapper;
    private final Path evidenceFile;

    public HistoricalTickEvidenceImportRunner(
            HistoricalTickCoverageService coverageService,
            ObjectMapper objectMapper,
            @Value("${voktrader.historical-tick.evidence-file}") String evidenceFile
    ) {
        this.coverageService = coverageService;
        this.objectMapper = objectMapper;
        this.evidenceFile = Path.of(evidenceFile).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!Files.isRegularFile(evidenceFile)) {
            throw new IllegalStateException("historical tick evidence file does not exist: " + evidenceFile);
        }

        HistoricalTickCoverageService.InventoryResult depthInventory =
                coverageService.inventoryDepthSnapshotCoverage();
        HistoricalTickCoverageService.PriceSnapshotInventoryResult priceInventory =
                coverageService.inventoryPriceSnapshotCoverage();

        int imported = 0;
        int metadataCreated = 0;
        int coverageCreated = 0;
        try (BufferedReader reader = Files.newBufferedReader(evidenceFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                EvidenceLine evidenceLine;
                try {
                    evidenceLine = objectMapper.readValue(trimmed, EvidenceLine.class);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException(
                            "invalid historical tick evidence at " + evidenceFile + ":" + lineNumber,
                            exception
                    );
                }
                HistoricalTickCoverageService.ImportResult result =
                        coverageService.importVerifiedEvidence(evidenceLine.toEvidence());
                imported++;
                if (result.metadataCreated()) {
                    metadataCreated++;
                }
                if (result.coverageCreated()) {
                    coverageCreated++;
                }
            }
        }

        log.info(
                "Historical tick evidence import complete: file={} imported={} metadataCreated={} coverageCreated={} "
                        + "depthBlockersCreated={} depthResolvedCreated={} priceBlockersCreated={}",
                evidenceFile,
                imported,
                metadataCreated,
                coverageCreated,
                depthInventory.blockersCreated(),
                depthInventory.resolvedIntervalsCreated(),
                priceInventory.blockersCreated()
        );
    }

    public record EvidenceLine(
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
        HistoricalTickCoverageService.VerifiedTickEvidence toEvidence() {
            return new HistoricalTickCoverageService.VerifiedTickEvidence(
                    datasetType,
                    datasetMarketId,
                    protocolMarketId,
                    tokenId,
                    intervalStartAt,
                    intervalEndAt,
                    tickSize,
                    evidenceSource,
                    sourceReference,
                    observedAt
            );
        }
    }
}
