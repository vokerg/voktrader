package com.vokerg.voktrader.paper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Deprecated
public interface SignalRepository extends JpaRepository<SignalEntity, Long> {

    boolean existsByMarketIdAndTokenIdAndRuleNameAndSignalType(
            String marketId,
            String tokenId,
            String ruleName,
            SignalType signalType
    );

    List<SignalEntity> findByMarketIdAndStatusAndSignalType(
            String marketId,
            SignalStatus status,
            SignalType signalType
    );

    List<SignalEntity> findByStatusAndSignalType(
            SignalStatus status,
            SignalType signalType
    );

    List<SignalEntity> findByStatusAndRuleNameAndSignalType(
            SignalStatus status,
            String ruleName,
            SignalType signalType
    );

    Optional<SignalEntity> findByIdAndStatusAndSignalType(
            Long id,
            SignalStatus status,
            SignalType signalType
    );
}
