package com.vokerg.voktrader.performance;

import org.springframework.stereotype.Service;

@Service
public class PerformanceService {

    public PerformanceSummary summary() {
        // TODO: calculate from SignalRepository.
        return new PerformanceSummary(0, 0, 0, java.math.BigDecimal.ZERO);
    }
}
