package com.vokerg.voktrader.paper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FakeSignalRepository extends JpaRepository<FakeSignalEntity, Long> {

    boolean existsByMarketIdAndTokenIdAndRuleName(
            String marketId,
            String tokenId,
            String ruleName
    );

    List<FakeSignalEntity> findByMarketIdAndStatus(
            String marketId,
            FakeSignalStatus status
    );

    List<FakeSignalEntity> findByStatus(FakeSignalStatus status);

    List<FakeSignalEntity> findByStatusAndRuleName(
            FakeSignalStatus status,
            String ruleName
    );

    Optional<FakeSignalEntity> findByIdAndStatus(
            Long id,
            FakeSignalStatus status
    );
}
