package com.vokerg.voktrader.polymarket.user;

import com.vokerg.voktrader.trade.model.ExecutionMode;
import com.vokerg.voktrader.trade.model.TradeOrderStatus;
import com.vokerg.voktrader.trade.model.TradeVenue;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserWebSocketSafetyService {
    private static final List<TradeOrderStatus> ACTIVE_LIVE_STATUSES = List.of(
            TradeOrderStatus.CREATED,
            TradeOrderStatus.SUBMITTING,
            TradeOrderStatus.SUBMITTED,
            TradeOrderStatus.RESTING,
            TradeOrderStatus.OPEN,
            TradeOrderStatus.PARTIALLY_FILLED,
            TradeOrderStatus.PARTIAL,
            TradeOrderStatus.CANCEL_REQUESTED,
            TradeOrderStatus.CANCEL_SUBMITTING,
            TradeOrderStatus.CANCEL_ACKNOWLEDGED,
            TradeOrderStatus.CANCEL_UNKNOWN,
            TradeOrderStatus.CANCEL_RECONCILE,
            TradeOrderStatus.UNKNOWN
    );

    private final TradeOrderRepository tradeOrderRepository;
    private final UserWebSocketEventRepository eventRepository;

    public UserWebSocketSafetyService(
            TradeOrderRepository tradeOrderRepository,
            UserWebSocketEventRepository eventRepository
    ) {
        this.tradeOrderRepository = tradeOrderRepository;
        this.eventRepository = eventRepository;
    }

    public ExposureSnapshot exposureSnapshot() {
        boolean activeLiveOrders = !tradeOrderRepository.findByModeAndVenueAndStatusInOrderByUpdatedAtAsc(
                ExecutionMode.LIVE,
                TradeVenue.POLYMARKET,
                ACTIVE_LIVE_STATUSES
        ).isEmpty();
        boolean unresolvedProvisionalTradeEvents = eventRepository.existsUnresolvedProvisionalTradeEvent();
        return new ExposureSnapshot(activeLiveOrders, unresolvedProvisionalTradeEvents);
    }

    public boolean requiresHealthyStream() {
        return exposureSnapshot().requiresHealthyStream();
    }

    public record ExposureSnapshot(
            boolean activeLiveOrders,
            boolean unresolvedProvisionalTradeEvents
    ) {
        public boolean requiresHealthyStream() {
            return activeLiveOrders || unresolvedProvisionalTradeEvents;
        }
    }
}
