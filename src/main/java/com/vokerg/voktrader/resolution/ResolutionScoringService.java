package com.vokerg.voktrader.resolution;

import org.springframework.stereotype.Service;

@Service
public class ResolutionScoringService {

    public void scoreResolvedMarket(Long marketId) {
        // TODO: find OPEN fake signals for market and mark WON or LOST.
    }
}
