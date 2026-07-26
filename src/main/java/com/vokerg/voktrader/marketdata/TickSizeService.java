package com.vokerg.voktrader.marketdata;

import com.vokerg.voktrader.marketdata.model.TickSizeMetadataEntity;
import com.vokerg.voktrader.marketdata.persistence.TickSizeMetadataRepository;
import com.vokerg.voktrader.time.TimeMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TickSizeService {
    private static final ThreadLocal<Map<String, TickSizeMetadata>> HISTORICAL_OVERRIDE = new ThreadLocal<>();

    private final TickSizeMetadataRepository repository;
    private final Map<String, TickSizeMetadata> currentByTokenId = new ConcurrentHashMap<>();

    public TickSizeService(TickSizeMetadataRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TickSizeMetadata recordRestBook(
            String tokenId,
            String marketId,
            String tickSize,
            Instant effectiveAt
    ) {
        return record(tokenId, marketId, tickSize, effectiveAt, TickSizeSource.REST_BOOK);
    }

    @Transactional
    public TickSizeMetadata recordTickSizeChange(
            String tokenId,
            String marketId,
            String oldTickSize,
            String newTickSize,
            Instant effectiveAt
    ) {
        if (oldTickSize != null && !oldTickSize.isBlank()) {
            TickMath.parseTick(oldTickSize);
        }
        return record(tokenId, marketId, newTickSize, effectiveAt, TickSizeSource.MARKET_WEBSOCKET);
    }

    public Optional<TickSizeMetadata> currentMetadata(String tokenId) {
        String requiredTokenId = requireTokenId(tokenId);
        Map<String, TickSizeMetadata> historical = HISTORICAL_OVERRIDE.get();
        if (historical != null) {
            return Optional.ofNullable(historical.get(requiredTokenId));
        }
        if (TimeMachine.isOverridden()) {
            return repository
                    .findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(requiredTokenId, TimeMachine.now())
                    .map(TickSizeMetadataEntity::toMetadata);
        }
        TickSizeMetadata cached = currentByTokenId.get(requiredTokenId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return repository.findFirstByTokenIdOrderByEffectiveAtDescIdDesc(requiredTokenId)
                .map(TickSizeMetadataEntity::toMetadata)
                .map(metadata -> {
                    currentByTokenId.put(requiredTokenId, metadata);
                    return metadata;
                });
    }

    public BigDecimal requireTickSize(String tokenId) {
        return currentMetadata(tokenId)
                .map(TickSizeMetadata::tickSize)
                .orElseThrow(() -> new IllegalStateException("tick metadata is unavailable for tokenId=" + tokenId));
    }

    public TickValidation validate(String tokenId, BigDecimal price) {
        Optional<TickSizeMetadata> metadata = currentMetadata(tokenId);
        if (metadata.isEmpty()) {
            return TickValidation.rejected(null, "tick metadata is unavailable for tokenId=" + tokenId);
        }
        BigDecimal tickSize = metadata.get().tickSize();
        if (!TickMath.isValidPrice(price, tickSize)) {
            return TickValidation.rejected(
                    tickSize,
                    "price " + TickMath.canonical(price) + " is not aligned to tick " + tickSize + " for tokenId=" + tokenId
            );
        }
        return TickValidation.accepted(tickSize, TickMath.canonical(price));
    }

    public BigDecimal requireValidPrice(String tokenId, BigDecimal price) {
        TickValidation validation = validate(tokenId, price);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.reason());
        }
        return validation.normalizedPrice();
    }

    public BigDecimal round(String tokenId, BigDecimal price, TickRounding rounding) {
        return TickMath.round(price, requireTickSize(tokenId), rounding);
    }

    public void runWithHistoricalTicks(Collection<String> tokenIds, Instant effectiveAt, Runnable action) {
        if (effectiveAt == null) {
            throw new IllegalArgumentException("historical tick effectiveAt is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("historical tick action is required");
        }
        Map<String, TickSizeMetadata> historical = new LinkedHashMap<>();
        for (String tokenId : tokenIds) {
            String requiredTokenId = requireTokenId(tokenId);
            TickSizeMetadata metadata = repository
                    .findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(requiredTokenId, effectiveAt)
                    .map(TickSizeMetadataEntity::toMetadata)
                    .orElseThrow(() -> new IllegalStateException(
                            "historical tick metadata is unavailable for tokenId=" + requiredTokenId + " at " + effectiveAt
                    ));
            historical.put(requiredTokenId, metadata);
        }

        Map<String, TickSizeMetadata> previous = HISTORICAL_OVERRIDE.get();
        HISTORICAL_OVERRIDE.set(Map.copyOf(historical));
        try {
            action.run();
        } finally {
            if (previous == null) {
                HISTORICAL_OVERRIDE.remove();
            } else {
                HISTORICAL_OVERRIDE.set(previous);
            }
        }
    }

    private TickSizeMetadata record(
            String tokenId,
            String marketId,
            String tickSize,
            Instant effectiveAt,
            TickSizeSource source
    ) {
        String requiredTokenId = requireTokenId(tokenId);
        BigDecimal parsedTick = TickMath.parseTick(tickSize);
        Instant resolvedEffectiveAt = effectiveAt == null ? Instant.now() : effectiveAt;
        Optional<TickSizeMetadata> atTime = repository
                .findFirstByTokenIdAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(requiredTokenId, resolvedEffectiveAt)
                .map(TickSizeMetadataEntity::toMetadata);
        if (atTime.isPresent() && atTime.get().tickSize().compareTo(parsedTick) == 0) {
            updateCurrentCache(atTime.get());
            return atTime.get();
        }

        TickSizeMetadata saved = repository.save(TickSizeMetadataEntity.observed(
                requiredTokenId,
                marketId,
                parsedTick,
                resolvedEffectiveAt,
                Instant.now(),
                source
        )).toMetadata();
        updateCurrentCache(saved);
        return saved;
    }

    private void updateCurrentCache(TickSizeMetadata candidate) {
        currentByTokenId.compute(candidate.tokenId(), (ignored, current) -> current == null
                || !candidate.effectiveAt().isBefore(current.effectiveAt()) ? candidate : current);
    }

    private String requireTokenId(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId is required for tick metadata");
        }
        return tokenId;
    }

    public record TickValidation(
            boolean valid,
            BigDecimal tickSize,
            BigDecimal normalizedPrice,
            String reason
    ) {
        public static TickValidation accepted(BigDecimal tickSize, BigDecimal normalizedPrice) {
            return new TickValidation(true, tickSize, normalizedPrice, null);
        }

        public static TickValidation rejected(BigDecimal tickSize, String reason) {
            return new TickValidation(false, tickSize, null, reason);
        }
    }
}
