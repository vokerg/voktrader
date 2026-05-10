package com.vokerg.voktrader.api.trade;

import com.vokerg.voktrader.api.trade.dto.TradeDetailResponse;
import com.vokerg.voktrader.api.trade.dto.TradeFillResponse;
import com.vokerg.voktrader.api.trade.dto.TradeSummaryResponse;
import com.vokerg.voktrader.trade.ExecutionMode;
import com.vokerg.voktrader.trade.TradeEntity;
import com.vokerg.voktrader.trade.TradeStatus;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class TradeQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final TradeRepository tradeRepository;
    private final TradeFillRepository tradeFillRepository;

    public TradeQueryService(TradeRepository tradeRepository, TradeFillRepository tradeFillRepository) {
        this.tradeRepository = tradeRepository;
        this.tradeFillRepository = tradeFillRepository;
    }

    public List<TradeSummaryResponse> list(Long botId, String strategyId, String marketId, String status, String mode, Integer limit) {
        int size = normalizeLimit(limit);
        Specification<TradeEntity> specification = specification(
                eq("botId", botId),
                eq("strategyId", normalizeBlank(strategyId)),
                eq("marketId", normalizeBlank(marketId)),
                eqEnum("status", status, TradeStatus.class),
                eqEnum("mode", mode, ExecutionMode.class)
        );
        return tradeRepository.findAll(specification, PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "updatedAt"))).stream()
                .map(TradeSummaryResponse::from)
                .toList();
    }

    public TradeDetailResponse get(Long id) {
        TradeEntity trade = tradeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown trade id: " + id));
        List<TradeFillResponse> fills = tradeFillRepository.findByTradeId(trade.getId()).stream()
                .map(TradeFillResponse::from)
                .toList();
        return TradeDetailResponse.from(trade, fills);
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

    private Specification<TradeEntity> eq(String field, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }

    private <E extends Enum<E>> Specification<TradeEntity> eqEnum(String field, String rawValue, Class<E> enumType) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        E parsed = Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        return eq(field, parsed);
    }

    @SafeVarargs
    private final Specification<TradeEntity> specification(Specification<TradeEntity>... parts) {
        Specification<TradeEntity> combined = null;
        for (Specification<TradeEntity> part : parts) {
            if (part == null) {
                continue;
            }
            combined = combined == null ? Specification.where(part) : combined.and(part);
        }
        return combined;
    }
}
