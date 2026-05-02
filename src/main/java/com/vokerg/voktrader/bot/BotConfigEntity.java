package com.vokerg.voktrader.bot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bot_configs", indexes = {
        @Index(name = "idx_bot_configs_enabled", columnList = "enabled"),
        @Index(name = "idx_bot_configs_family", columnList = "market_family")
})
public class BotConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_family", nullable = false, length = 32)
    private MarketFamily marketFamily;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BotStatus status;

    @Column(name = "last_error", length = 4000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BotConfigEntity() {
    }

    public static BotConfigEntity create(String name, MarketFamily marketFamily, String strategyId, boolean enabled) {
        Instant now = Instant.now();
        BotConfigEntity entity = new BotConfigEntity();
        entity.name = name;
        entity.marketFamily = marketFamily;
        entity.strategyId = strategyId;
        entity.enabled = enabled;
        entity.status = enabled ? BotStatus.RUNNING : BotStatus.PAUSED;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void switchTo(MarketFamily marketFamily, String strategyId, Boolean enabled) {
        if (marketFamily != null) {
            this.marketFamily = marketFamily;
        }
        if (strategyId != null && !strategyId.isBlank()) {
            this.strategyId = strategyId.trim();
        }
        if (enabled != null) {
            this.enabled = enabled;
            this.status = enabled ? BotStatus.RUNNING : BotStatus.PAUSED;
        }
        this.lastError = null;
        touch();
    }

    public void pause() {
        this.enabled = false;
        this.status = BotStatus.PAUSED;
        touch();
    }

    public void resume() {
        this.enabled = true;
        this.status = BotStatus.RUNNING;
        this.lastError = null;
        touch();
    }

    public void markError(Throwable throwable) {
        this.status = BotStatus.ERROR;
        this.lastError = throwable == null ? null : throwable.getMessage();
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public MarketFamily getMarketFamily() { return marketFamily; }
    public String getStrategyId() { return strategyId; }
    public BotStatus getStatus() { return status; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; touch(); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; this.status = enabled ? BotStatus.RUNNING : BotStatus.PAUSED; touch(); }
    public void setMarketFamily(MarketFamily marketFamily) { this.marketFamily = marketFamily; touch(); }
    public void setStrategyId(String strategyId) { this.strategyId = strategyId; touch(); }
    public void setStatus(BotStatus status) { this.status = status; touch(); }
    public void setLastError(String lastError) { this.lastError = lastError; touch(); }
}
