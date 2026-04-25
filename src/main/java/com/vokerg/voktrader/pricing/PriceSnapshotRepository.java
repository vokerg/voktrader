package com.vokerg.voktrader.pricing;


import org.springframework.data.jpa.repository.JpaRepository;


public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshotEntity, Long> {
}
