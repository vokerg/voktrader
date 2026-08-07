package com.vokerg.voktrader.trade.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokerg.voktrader.trade.TradeIntent;
import com.vokerg.voktrader.trade.model.ExecutionMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class TransactionalOrderIntentService {
    private static final String CLIENT_ORDER_PREFIX = "vok-";
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final List<OrderDispatchState> EXPOSURE_BLOCKING_STATES = List.of(
            OrderDispatchState.UNKNOWN,
            OrderDispatchState.RECONCILE,
            OrderDispatchState.MANUAL_REVIEW
    );

    private final OrderIntentRepository intentRepository;
    private final OrderDispatchOutboxRepository dispatchRepository;

    public TransactionalOrderIntentService(
            OrderIntentRepository intentRepository,
            OrderDispatchOutboxRepository dispatchRepository
    ) {
        this.intentRepository = intentRepository;
        this.dispatchRepository = dispatchRepository;
    }

    @Transactional
    public AcceptedOrderIntent accept(TradeIntent intent, ExecutionMode mode, String riskDecisionId) {
        Objects.requireNonNull(intent, "intent is required");
        Objects.requireNonNull(mode, "mode is required");
        requireText(riskDecisionId, "riskDecisionId");

        String payloadJson = serialize(intent);
        String intentHash = sha256(payloadJson);
        String clientOrderId = clientOrderId(riskDecisionId, intentHash);

        Optional<OrderIntentEntity> existing = intentRepository.findByClientOrderId(clientOrderId);
        if (existing.isPresent()) {
            return existingAcceptance(existing.orElseThrow(), intentHash, riskDecisionId, mode);
        }
        if (dispatchRepository.existsByStateIn(EXPOSURE_BLOCKING_STATES)) {
            throw new IllegalStateException(
                    "Order acceptance blocked while an earlier submission outcome is unresolved"
            );
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OrderIntentEntity storedIntent = intentRepository.save(OrderIntentEntity.accepted(
                clientOrderId, intentHash, riskDecisionId, mode, intent.side(), payloadJson, now
        ));
        OrderDispatchOutboxEntity dispatch = dispatchRepository.save(OrderDispatchOutboxEntity.ready(storedIntent, now));
        return toAcceptance(storedIntent, dispatch);
    }

    public String clientOrderIdFor(TradeIntent intent, String riskDecisionId) {
        Objects.requireNonNull(intent, "intent is required");
        requireText(riskDecisionId, "riskDecisionId");
        return clientOrderId(riskDecisionId, sha256(serialize(intent)));
    }

    @Transactional(readOnly = true)
    public Optional<AcceptedOrderIntent> recover(String clientOrderId) {
        requireText(clientOrderId, "clientOrderId");
        return intentRepository.findByClientOrderId(clientOrderId)
                .map(intent -> toAcceptance(intent, dispatchRepository.findByClientOrderId(clientOrderId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing dispatch row for accepted order intent " + clientOrderId
                        ))));
    }

    private AcceptedOrderIntent existingAcceptance(
            OrderIntentEntity existing, String intentHash, String riskDecisionId, ExecutionMode mode
    ) {
        if (!existing.getIntentHash().equals(intentHash)
                || !existing.getRiskDecisionId().equals(riskDecisionId)
                || existing.getExecutionMode() != mode) {
            throw new IllegalStateException("clientOrderId collision for " + existing.getClientOrderId());
        }
        OrderDispatchOutboxEntity dispatch = dispatchRepository.findByClientOrderId(existing.getClientOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing dispatch row for accepted order intent " + existing.getClientOrderId()
                ));
        return toAcceptance(existing, dispatch);
    }

    private AcceptedOrderIntent toAcceptance(OrderIntentEntity intent, OrderDispatchOutboxEntity dispatch) {
        return new AcceptedOrderIntent(
                intent.getId(), dispatch.getId(), intent.getClientOrderId(), intent.getIntentHash(),
                intent.getRiskDecisionId(), intent.getExecutionMode(), intent.getSide(), intent.getState(),
                dispatch.getState(), intent.getAcceptedAt()
        );
    }

    private String serialize(TradeIntent intent) {
        try {
            return PAYLOAD_MAPPER.writeValueAsString(intent);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Order intent is not serializable", exception);
        }
    }

    private static String clientOrderId(String riskDecisionId, String intentHash) {
        return CLIENT_ORDER_PREFIX + sha256(riskDecisionId + ":" + intentHash).substring(0, 40);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
