package com.vokerg.voktrader.market;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TrackedMarketState {

    private final AtomicReference<GammaMarketDto> currentMarket = new AtomicReference<>();

    public void setCurrentMarket(GammaMarketDto market) {
        currentMarket.set(market);
    }

    public Optional<GammaMarketDto> currentMarket() {
        return Optional.ofNullable(currentMarket.get());
    }

    public Optional<Instant> currentEndDate() {
        return currentMarket()
                .map(GammaMarketDto::endDate);
    }
}