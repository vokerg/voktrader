package com.vokerg.voktrader.market;

import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MarketPersistenceService {

    private final MarketRepository marketRepository;

    @Transactional
    public MarketEntity saveOrUpdate(GammaMarketDto market) {
        Instant now = Instant.now();

        MarketEntity entity = marketRepository.findByPolymarketMarketId(market.id())
                .orElseGet(() -> {
                    MarketEntity created = new MarketEntity();
                    created.setPolymarketMarketId(market.id());
                    created.setFirstSeenAt(now);
                    return created;
                });

        entity.setConditionId(market.conditionId());
        entity.setQuestion(market.question());
        entity.setSlug(market.slug());
        entity.setEndDate(market.endDate());
        entity.setActive(Boolean.TRUE.equals(market.active()));
        entity.setClosed(Boolean.TRUE.equals(market.closed()));
        entity.setAcceptingOrders(Boolean.TRUE.equals(market.acceptingOrders()));
        entity.setLastSeenAt(now);

        return marketRepository.save(entity);
    }
}
