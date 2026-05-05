package com.vokerg.voktrader.marketdata;


import org.springframework.data.jpa.repository.JpaRepository;


public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshotEntity, Long> {
}
