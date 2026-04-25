package com.vokerg.voktrader.paper;

import com.vokerg.voktrader.strategy.SignalDecision;
import org.springframework.stereotype.Service;

@Service
public class FakeSignalService {

    private final FakeSignalRepository fakeSignalRepository;

    public FakeSignalService(FakeSignalRepository fakeSignalRepository) {
        this.fakeSignalRepository = fakeSignalRepository;
    }

    public void saveFakeSignal(Long marketId, SignalDecision decision) {
        if (!decision.shouldBuy()) {
            return;
        }

        // TODO: create and save FakeSignalEntity.
    }
}
