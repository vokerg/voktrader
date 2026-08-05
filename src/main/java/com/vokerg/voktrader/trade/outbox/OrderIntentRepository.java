package com.vokerg.voktrader.trade.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderIntentRepository extends JpaRepository<OrderIntentEntity, Long> {
    Optional<OrderIntentEntity> findByClientOrderId(String clientOrderId);
}
