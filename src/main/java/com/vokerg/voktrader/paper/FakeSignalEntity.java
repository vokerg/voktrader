package com.vokerg.voktrader.paper;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fake_signals")
public class FakeSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long marketId;

    @Enumerated(EnumType.STRING)
    private Side side;

    @Enumerated(EnumType.STRING)
    private FakeSignalStatus status = FakeSignalStatus.OPEN;

    private BigDecimal fakeEntryPrice;
    private BigDecimal fakeSize;
    private BigDecimal fakePnl;
    private Instant createdAt;

    public Long getId() {
        return id;
    }
}
