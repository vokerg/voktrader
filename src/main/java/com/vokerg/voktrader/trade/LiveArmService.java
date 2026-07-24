package com.vokerg.voktrader.trade;

import com.vokerg.voktrader.executor.ExecutorProperties;
import com.vokerg.voktrader.time.TimeMachine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class LiveArmService {
    private static final String DEFAULT_EXECUTOR_TOKEN = "change-me";
    private static final String DEFAULT_ACCOUNT_ID = "change-me";

    private final TradingProperties tradingProperties;
    private final ExecutorProperties executorProperties;

    private Instant armedAt;
    private Instant expiresAt;
    private String armedAccountId;

    public LiveArmService(TradingProperties tradingProperties, ExecutorProperties executorProperties) {
        this.tradingProperties = tradingProperties;
        this.executorProperties = executorProperties;
    }

    public synchronized LiveArmStatus arm(String accountId) {
        Instant now = TimeMachine.now();
        LiveArmStatus current = statusAt(now);
        if (!current.capabilityReady()) {
            throw new IllegalStateException("LIVE capability is not ready: " + String.join("; ", current.capabilityBlockers()));
        }

        String expectedAccountId = normalize(tradingProperties.getExpectedAccountId());
        String requestedAccountId = normalize(accountId);
        if (!expectedAccountId.equals(requestedAccountId)) {
            throw new IllegalArgumentException("LIVE arm account does not match voktrader.trading.expected-account-id");
        }

        Duration ttl = tradingProperties.getLiveArmTtl();
        armedAt = now;
        expiresAt = now.plus(ttl);
        armedAccountId = expectedAccountId;
        return statusAt(now);
    }

    public synchronized LiveArmStatus disarm() {
        armedAt = null;
        expiresAt = null;
        armedAccountId = null;
        return statusAt(TimeMachine.now());
    }

    public synchronized LiveArmStatus status() {
        return statusAt(TimeMachine.now());
    }

    private LiveArmStatus statusAt(Instant now) {
        List<String> capabilityBlockers = capabilityBlockers();
        boolean capabilityReady = capabilityBlockers.isEmpty();
        boolean armed = expiresAt != null
                && armedAccountId != null
                && now.isBefore(expiresAt);
        boolean entryAllowed = capabilityReady && armed;

        List<String> entryBlockers = new ArrayList<>(capabilityBlockers);
        if (!armed) {
            if (expiresAt == null) {
                entryBlockers.add("live arm is not active");
            } else {
                entryBlockers.add("live arm expired at " + expiresAt);
            }
        }

        return new LiveArmStatus(
                armed,
                armedAt,
                expiresAt,
                armedAccountId,
                capabilityReady,
                executorTokenConfigured(),
                expectedAccountConfigured(),
                entryAllowed,
                List.copyOf(capabilityBlockers),
                List.copyOf(entryBlockers)
        );
    }

    private List<String> capabilityBlockers() {
        List<String> blockers = new ArrayList<>();
        if (tradingProperties.getMode() != com.vokerg.voktrader.trade.model.ExecutionMode.LIVE) {
            blockers.add("trading mode is not LIVE");
        }
        if (!tradingProperties.isLiveEnabled()) {
            blockers.add("live trading capability is disabled");
        }
        if (tradingProperties.isKillSwitchEnabled()) {
            blockers.add("kill switch is enabled");
        }
        if (!executorProperties.isEnabled()) {
            blockers.add("executor is disabled");
        }
        if (executorProperties.isDryRun()) {
            blockers.add("executor is in dry-run mode");
        }
        if (!executorTokenConfigured()) {
            blockers.add("executor API token is blank or still uses the default value");
        }
        if (!expectedAccountConfigured()) {
            blockers.add("expected live account metadata is not configured");
        }
        Duration ttl = tradingProperties.getLiveArmTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            blockers.add("live arm TTL must be positive");
        }
        return blockers;
    }

    private boolean executorTokenConfigured() {
        String token = normalize(executorProperties.getApiToken());
        return !token.isEmpty() && !DEFAULT_EXECUTOR_TOKEN.equalsIgnoreCase(token);
    }

    private boolean expectedAccountConfigured() {
        String accountId = normalize(tradingProperties.getExpectedAccountId());
        return !accountId.isEmpty() && !DEFAULT_ACCOUNT_ID.equalsIgnoreCase(accountId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record LiveArmStatus(
            boolean armed,
            Instant armedAt,
            Instant expiresAt,
            String armedAccountId,
            boolean capabilityReady,
            boolean executorTokenConfigured,
            boolean expectedAccountConfigured,
            boolean entryAllowed,
            List<String> capabilityBlockers,
            List<String> entryBlockers
    ) {
        public String entryBlockReason() {
            return entryBlockers.isEmpty() ? null : String.join("; ", entryBlockers);
        }
    }
}
