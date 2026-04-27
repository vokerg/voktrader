package com.vokerg.voktrader.pricing;

import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class OrderBookService {

    public BigDecimal bestBid(OrderBookDto orderBook) {
        return orderBook.bestBid().orElse(null);
    }

    public BigDecimal bestAsk(OrderBookDto orderBook) {
        return orderBook.bestAsk().orElse(null);
    }

    public BigDecimal spread(OrderBookDto orderBook) {
        return orderBook.spread().orElse(null);
    }
}
