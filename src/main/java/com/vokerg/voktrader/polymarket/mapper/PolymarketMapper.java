package com.vokerg.voktrader.polymarket.mapper;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PolymarketMapper {

    public MarketEntity toMarketEntity(GammaMarketDto dto) {
        Instant now = Instant.now();

        MarketEntity entity = new MarketEntity();
        entity.setPolymarketMarketId(dto.id());
        entity.setConditionId(dto.conditionId());
        entity.setQuestion(dto.question());
        entity.setSlug(dto.slug());
        entity.setEndDate(dto.endDate());
        entity.setActive(Boolean.TRUE.equals(dto.active()));
        entity.setClosed(Boolean.TRUE.equals(dto.closed()));
        entity.setAcceptingOrders(Boolean.TRUE.equals(dto.acceptingOrders()));
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        return entity;
    }
}
