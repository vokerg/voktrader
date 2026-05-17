package com.vokerg.voktrader.api.trade;

import com.vokerg.voktrader.api.trade.dto.TradeEventResponse;
import com.vokerg.voktrader.api.trade.dto.TradeFillResponse;
import com.vokerg.voktrader.api.trade.dto.TradeOrderDetailResponse;
import com.vokerg.voktrader.api.trade.dto.TradeOrderResponse;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeOrderPhase;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeOrderType;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.persistence.TradeEventRepository;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class OrderQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final TradeEventRepository tradeEventRepository;

    public OrderQueryService(
            TradeOrderRepository tradeOrderRepository,
            TradeFillRepository tradeFillRepository,
            TradeEventRepository tradeEventRepository
    ) {
        this.tradeOrderRepository = tradeOrderRepository;
        this.tradeFillRepository = tradeFillRepository;
        this.tradeEventRepository = tradeEventRepository;
    }

    public List<TradeOrderResponse> list(Long botId, String strategyId, String marketId, String status, String side, String orderType, String phase, String mode, Integer limit) {
        int size = normalizeLimit(limit);
        Specification<TradeOrderEntity> specification = specification(
                eq("botId", botId),
                eq("strategyId", normalizeBlank(strategyId)),
                eq("marketId", normalizeBlank(marketId)),
                eqEnum("status", status, TradeOrderStatus.class),
                eqEnum("side", side, TradeSide.class),
                eqEnum("orderType", orderType, TradeOrderType.class),
                eqEnum("phase", phase, TradeOrderPhase.class),
                eqEnum("mode", mode, ExecutionMode.class)
        );
        return tradeOrderRepository.findAll(specification, PageRequest.of(0, size, Sort.by("id").descending())).stream()
                .map(TradeOrderResponse::from)
                .toList();
    }

    public TradeOrderDetailResponse get(Long id) {
        TradeOrderEntity order = tradeOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order id: " + id));
        
        List<TradeFillResponse> fills = tradeFillRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(TradeFillResponse::from)
                .toList();
        
        List<TradeEventResponse> events = tradeEventRepository.findByTradeOrderIdOrderByCreatedAtAsc(order.getId()).stream()
                .map(TradeEventResponse::from)
                .toList();
                
        return new TradeOrderDetailResponse(TradeOrderResponse.from(order), fills, events);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Specification<TradeOrderEntity> eq(String field, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }

    private <E extends Enum<E>> Specification<TradeOrderEntity> eqEnum(String field, String rawValue, Class<E> enumType) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        E parsed = Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        return eq(field, parsed);
    }

    @SafeVarargs
    private final Specification<TradeOrderEntity> specification(Specification<TradeOrderEntity>... parts) {
        Specification<TradeOrderEntity> combined = null;
        for (Specification<TradeOrderEntity> part : parts) {
            if (part == null) {
                continue;
            }
            combined = combined == null ? Specification.where(part) : combined.and(part);
        }
        return combined;
    }
}
