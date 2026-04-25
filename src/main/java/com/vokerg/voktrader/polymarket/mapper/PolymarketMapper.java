package com.vokerg.voktrader.polymarket.mapper;

import com.vokerg.voktrader.market.MarketEntity;
import com.vokerg.voktrader.polymarket.dto.GammaMarketDto;
import org.springframework.stereotype.Component;

@Component
public class PolymarketMapper {

    public MarketEntity toMarketEntity(GammaMarketDto dto) {
        MarketEntity entity = new MarketEntity();
        entity.setPolymarketMarketId(dto.id());
        entity.setQuestion(dto.question());
        entity.setSlug(dto.slug());
        entity.setActive(dto.active());
        return entity;
    }
}
