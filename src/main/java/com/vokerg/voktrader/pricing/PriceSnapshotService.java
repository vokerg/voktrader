package com.vokerg.voktrader.pricing;

import com.vokerg.voktrader.polymarket.dto.OrderBookDto;
import org.springframework.stereotype.Service;

@Service
public class PriceSnapshotService {

    private final PriceSnapshotRepository priceSnapshotRepository;
    private final OrderBookService orderBookService;

    public PriceSnapshotService(
            PriceSnapshotRepository priceSnapshotRepository,
            OrderBookService orderBookService
    ) {
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.orderBookService = orderBookService;
    }

    public void saveSnapshot(Long marketId, OrderBookDto orderBook) {
        // TODO: create PriceSnapshotEntity with best bid, best ask, spread.
    }
}
