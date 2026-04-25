package com.vokerg.voktrader.pricing;

import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import com.vokerg.voktrader.polymarket.dto.PriceLevelDto;
import java.math.BigDecimal;
import java.util.Comparator;
import org.springframework.stereotype.Service;

@Service
public class OrderBookService {

    public BigDecimal bestBid(OrderBookDto orderBook) {
        return orderBook.bids()
                .stream()
                .map(PriceLevelDto::price)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    public BigDecimal bestAsk(OrderBookDto orderBook) {
        return orderBook.asks()
                .stream()
                .map(PriceLevelDto::price)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    public BigDecimal spread(OrderBookDto orderBook) {
        BigDecimal bid = bestBid(orderBook);
        BigDecimal ask = bestAsk(orderBook);

        if (bid == null || ask == null) {
            return null;
        }

        return ask.subtract(bid);
    }
}
