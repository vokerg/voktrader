package com.vokerg.voktrader.marketdata.persistence;

import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface TickSizeMetadataRepository extends JpaRepository<TickSizeMetadataEntity, Long> {
    Optional<TickSizeMetadataEntity> findFirstByTokenIdOrderByEffectiveAtAscIdAsc(String tokenId);

    Optional<TickSizeMetadataEntity> findFirstByTokenIdOrderByEffectiveAtDescIdDesc(String tokenId);

    Optional<TickSizeMetadataEntity> findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
            String tokenId,
            Instant effectiveAt
    );
}
