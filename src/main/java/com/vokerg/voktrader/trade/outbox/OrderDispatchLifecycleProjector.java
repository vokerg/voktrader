package com.vokerg.voktrader.trade.outbox;

import com.vokerg.voktrader.executor.ExecutorOrderResponse;
import com.vokerg.voktrader.trade.OrderReconciliationService;
import com.vokerg.voktrader.trade.model.TradeEntity;
import com.vokerg.voktrader.trade.model.TradeFillEntity;
import com.vokerg.voktrader.trade.model.TradeOrderEntity;
import com.vokerg.voktrader.trade.model.TradeSide;
import com.vokerg.voktrader.trade.persistence.TradeFillRepository;
import com.vokerg.voktrader.trade.persistence.TradeOrderRepository;
import com.vokerg.voktrader.trade.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Projects authoritative outbox transitions into the existing order lifecycle. */
@Component
@RequiredArgsConstructor
public class OrderDispatchLifecycleProjector {
    private final TradeRepository tradeRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final OrderReconciliationService reconciliationService;

    void markSubmitting(OrderDispatchClaim claim) {
        tradeOrderRepository.findByClientOrderId(claim.clientOrderId()).ifPresent(order -> {
            order.markSubmitting(claim.clientOrderId(), claim.payloadJson());
            tradeOrderRepository.save(order);
        });
    }

    void recordResponse(OrderDispatchClaim claim, ExecutorOrderResponse response) {
        tradeOrderRepository.findByClientOrderId(claim.clientOrderId()).ifPresent(order -> {
            TradeEntity trade = linkedTrade(order);
            order.attachExecutorResponse(response.exchangeOrderId(), response.rawResponse());
            if (!response.accepted()) {
                order.markRejected(response.safeMessage(), response.rawResponse());
                tradeOrderRepository.save(order);
                if (trade != null) {
                    if (order.getSide() == TradeSide.BUY) {
                        trade.markFailed(response.safeMessage());
                    } else {
                        restorePositionAfterExitFailure(trade);
                    }
                    tradeRepository.save(trade);
                }
                return;
            }

            if (response.filled() && trade != null) {
                persistImmediateFillIfAbsent(trade, order, response);
                reconciliationService.applyImmediateFill(trade, order, response);
                return;
            }

            order.markSubmitted(response.exchangeOrderId(), response.rawResponse());
            tradeOrderRepository.save(order);
            if (trade != null) {
                if (order.getSide() == TradeSide.BUY) {
                    trade.markEntryPending();
                } else {
                    trade.markExitPending();
                }
                tradeRepository.save(trade);
            }
        });
    }

    void recordAmbiguousFailure(String clientOrderId, String details) {
        tradeOrderRepository.findByClientOrderId(clientOrderId).ifPresent(order -> {
            order.markUnknown(details);
            tradeOrderRepository.save(order);
        });
    }

    void markReconcile(String clientOrderId, String details) {
        recordAmbiguousFailure(clientOrderId, details);
    }

    void resolveAccepted(String clientOrderId, String remoteOrderId, String details) {
        tradeOrderRepository.findByClientOrderId(clientOrderId).ifPresent(order -> {
            order.markSubmitted(remoteOrderId, details);
            tradeOrderRepository.save(order);
            TradeEntity trade = linkedTrade(order);
            if (trade != null) {
                if (order.getSide() == TradeSide.BUY) {
                    trade.markEntryPending();
                } else {
                    trade.markExitPending();
                }
                tradeRepository.save(trade);
            }
        });
    }

    void resolveRejected(String clientOrderId, String details) {
        tradeOrderRepository.findByClientOrderId(clientOrderId).ifPresent(order -> {
            order.markRejected(details, details);
            tradeOrderRepository.save(order);
            TradeEntity trade = linkedTrade(order);
            if (trade != null) {
                if (order.getSide() == TradeSide.BUY) {
                    trade.markFailed(details);
                } else {
                    restorePositionAfterExitFailure(trade);
                }
                tradeRepository.save(trade);
            }
        });
    }

    private void restorePositionAfterExitFailure(TradeEntity trade) {
        BigDecimal exitedShares = zeroIfNull(trade.getExitFilledShares());
        if (exitedShares.signum() > 0) {
            trade.markPartiallyClosed(
                    trade.getExitAvgPrice(),
                    trade.getExitFilledShares(),
                    trade.getExitFilledUsd(),
                    trade.getExitFeeUsd(),
                    trade.getExitCompletedAt()
            );
            return;
        }

        BigDecimal entryShares = zeroIfNull(trade.getEntryFilledShares());
        BigDecimal intendedShares = zeroIfNull(trade.getIntendedShares());
        if (intendedShares.signum() > 0 && entryShares.compareTo(intendedShares) < 0) {
            trade.markPartiallyOpen(
                    trade.getEntryAvgPrice(),
                    trade.getEntryFilledShares(),
                    trade.getEntryFilledUsd(),
                    trade.getEntryFeeUsd(),
                    trade.getEntryCompletedAt()
            );
        } else {
            trade.markOpen(
                    trade.getEntryAvgPrice(),
                    trade.getEntryFilledShares(),
                    trade.getEntryFilledUsd(),
                    trade.getEntryFeeUsd(),
                    trade.getEntryCompletedAt()
            );
        }
    }

    private TradeEntity linkedTrade(TradeOrderEntity order) {
        return order.getTradeId() == null ? null : tradeRepository.findById(order.getTradeId()).orElse(null);
    }

    private void persistImmediateFillIfAbsent(
            TradeEntity trade,
            TradeOrderEntity order,
            ExecutorOrderResponse response
    ) {
        BigDecimal price = firstNonNull(response.averagePrice(), order.getRequestedPrice());
        BigDecimal shares = firstNonNull(response.filledShares(), order.getRequestedShares());
        BigDecimal amountUsd = firstNonNull(
                response.filledAmountUsd(),
                price == null || shares == null ? null : price.multiply(shares)
        );
        boolean alreadyRecorded = tradeFillRepository.findByOrderId(order.getId()).stream()
                .anyMatch(fill -> equalString(response.exchangeOrderId(), fill.getExchangeOrderId())
                        && order.getSide() == fill.getSide()
                        && equalByValue(price, fill.getPrice())
                        && equalByValue(shares, fill.getShares()));
        if (alreadyRecorded) {
            return;
        }
        tradeFillRepository.save(TradeFillEntity.polymarket(
                trade.getId(), order.getId(), response.exchangeOrderId(), order.getSide(),
                price, shares, amountUsd, response.feeUsd(), response.rawResponse()
        ));
    }

    private <T> T firstNonNull(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean equalByValue(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private boolean equalString(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equals(right);
    }
}
