package com.vokerg.voktrader.trade.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderDispatchOutboxRepository extends JpaRepository<OrderDispatchOutboxEntity, Long> {
    Optional<OrderDispatchOutboxEntity> findByClientOrderId(String clientOrderId);
}
