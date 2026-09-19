package com.vokerg.voktrader.polymarket.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserWebSocketEventRepository extends JpaRepository<UserWebSocketEventEntity, Long> {
    boolean existsByDedupeKey(String dedupeKey);

    @Query("""
            select count(e) > 0
            from UserWebSocketEventEntity e
            where lower(e.eventType) = 'trade'
              and upper(e.lifecycleStatus) in ('MATCHED', 'MINED', 'RETRYING')
              and e.remoteTradeId is not null
              and not exists (
                    select terminal.id
                    from UserWebSocketEventEntity terminal
                    where terminal.remoteTradeId = e.remoteTradeId
                      and upper(terminal.lifecycleStatus) in ('CONFIRMED', 'FAILED')
              )
            """)
    boolean existsUnresolvedProvisionalTradeEvent();
}
