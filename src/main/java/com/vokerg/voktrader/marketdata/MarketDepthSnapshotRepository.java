package com.vokerg.voktrader.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketDepthSnapshotRepository extends JpaRepository<MarketDepthSnapshotEntity, Long> {
}
