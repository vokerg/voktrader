package com.vokerg.voktrader.fee;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class FeeService {
    private final FeeMetadataRepository repository;
    private final FeeModel feeModel;

    public FeeService(FeeMetadataRepository repository, FeeModel feeModel) {
        this.repository = repository;
        this.feeModel = feeModel;
    }

    @Transactional
    public FeeMetadata record(FeeMetadata metadata) {
        return repository.save(new FeeMetadataEntity(metadata)).toValue();
    }

    @Transactional(readOnly = true)
    public FeeMetadata requireLatest(String marketId) {
        return repository.findFirstByMarketIdOrderByEffectiveAtDesc(marketId)
                .map(FeeMetadataEntity::toValue)
                .orElseThrow(() -> new IllegalStateException("Missing fee metadata for market " + marketId));
    }

    @Transactional(readOnly = true)
    public FeeMetadata requireAsOf(String marketId, Instant effectiveAt) {
        return repository.findFirstByMarketIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(marketId, effectiveAt)
                .map(FeeMetadataEntity::toValue)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing fee metadata for market " + marketId + " at " + effectiveAt));
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateLatest(
            String marketId,
            FeeLiquidityRole role,
            BigDecimal price,
            BigDecimal shares
    ) {
        return feeModel.calculate(requireLatest(marketId), role, price, shares);
    }

    public String modelVersion() {
        return feeModel.version();
    }
}
