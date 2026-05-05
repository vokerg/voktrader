package com.vokerg.voktrader.bot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotConfigRepository extends JpaRepository<BotConfigEntity, Long> {
    List<BotConfigEntity> findAllByEnabledTrueOrderByIdAsc();
    List<BotConfigEntity> findAllByOrderByIdAsc();
    Optional<BotConfigEntity> findByName(String name);
}
