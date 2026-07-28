package com.vokerg.voktrader.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface FeeMetadataRepository extends JpaRepository<FeeMetadataEntity, Long> {
    Optional<FeeMetadataEntity> findFirstByMarketIdOrderByEffectiveAtDesc(String marketId);

    Optional<FeeMetadataEntity> findFirstByMarketIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
            String marketId,
            Instant effectiveAt
    );
}
