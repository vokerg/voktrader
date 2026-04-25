package com.vokerg.voktrader.dashboard;

import com.vokerg.voktrader.performance.PerformanceService;
import com.vokerg.voktrader.performance.PerformanceSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final PerformanceService performanceService;

    public DashboardController(PerformanceService performanceService) {
        this.performanceService = performanceService;
    }

    @GetMapping("/summary")
    public PerformanceSummary summary() {
        return performanceService.summary();
    }
}
