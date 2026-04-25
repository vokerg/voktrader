package com.vokerg.voktrader.market;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "markets")
public class MarketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String polymarketMarketId;
    private String question;
    private String slug;
    private boolean active;

    public Long getId() {
        return id;
    }

    public String getPolymarketMarketId() {
        return polymarketMarketId;
    }

    public void setPolymarketMarketId(String polymarketMarketId) {
        this.polymarketMarketId = polymarketMarketId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
