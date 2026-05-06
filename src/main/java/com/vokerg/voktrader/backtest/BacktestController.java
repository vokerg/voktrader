package com.vokerg.voktrader.backtest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backtests")
@RequiredArgsConstructor
public class BacktestController {
    private final BacktestReplayService replayService;

    @PostMapping
    public BacktestResponse run(@Valid @RequestBody BacktestRequest request) {
        return replayService.run(request);
    }
}
