package com.vokerg.voktrader.strategy;

import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import com.vokerg.voktrader.pricing.OrderBookService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class SignalRuleEngine {

    private final BuyOnlyRule buyOnlyRule;
    private final OrderBookService orderBookService;

    public SignalRuleEngine(BuyOnlyRule buyOnlyRule, OrderBookService orderBookService) {
        this.buyOnlyRule = buyOnlyRule;
        this.orderBookService = orderBookService;
    }

    public SignalDecision evaluate(OrderBookDto orderBook) {
        BigDecimal bestAsk = orderBookService.bestAsk(orderBook);
        BigDecimal spread = orderBookService.spread(orderBook);

        return buyOnlyRule.evaluate(bestAsk, spread);
    }
}
