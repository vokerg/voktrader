package com.vokerg.voktrader.backtest;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, String> {
}
