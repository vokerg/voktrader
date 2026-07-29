package com.vokerg.voktrader.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "voktrader.control-plane")
public class ControlPlaneProperties {
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String CONFIRMATION_HEADER = "X-Voktrader-Confirmation";
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private String readOnlyToken;
    private String operatorToken;
    private String adminToken;
    private String confirmationToken;

    public String getReadOnlyToken() {
        return readOnlyToken;
    }

    public void setReadOnlyToken(String readOnlyToken) {
        this.readOnlyToken = readOnlyToken;
    }

    public String getOperatorToken() {
        return operatorToken;
    }

    public void setOperatorToken(String operatorToken) {
        this.operatorToken = operatorToken;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public String getConfirmationToken() {
        return confirmationToken;
    }

    public void setConfirmationToken(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    public void validateForLive() {
        List<String> secrets = List.of(
                requireSecret("read-only token", readOnlyToken),
                requireSecret("operator token", operatorToken),
                requireSecret("admin token", adminToken),
                requireSecret("confirmation token", confirmationToken)
        );
        Set<String> unique = new HashSet<>(secrets);
        if (unique.size() != secrets.size()) {
            throw new IllegalStateException("Live control-plane tokens must be distinct");
        }
    }

    public ControlPlaneRole authenticate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (constantTimeEquals(readOnlyToken, candidate)) {
            return ControlPlaneRole.READ_ONLY;
        }
        if (constantTimeEquals(operatorToken, candidate)) {
            return ControlPlaneRole.OPERATOR;
        }
        if (constantTimeEquals(adminToken, candidate)) {
            return ControlPlaneRole.ADMIN;
        }
        return null;
    }

    public boolean confirmationMatches(String candidate) {
        return constantTimeEquals(confirmationToken, candidate);
    }

    private String requireSecret(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Live control-plane " + label + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException("Live control-plane " + label + " must be at least " + MINIMUM_SECRET_LENGTH + " characters");
        }
        return normalized;
    }

    private boolean constantTimeEquals(String expected, String candidate) {
        if (expected == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    public enum ControlPlaneRole {
        READ_ONLY("control-plane-read-only", "ROLE_READ_ONLY"),
        OPERATOR("control-plane-operator", "ROLE_OPERATOR"),
        ADMIN("control-plane-admin", "ROLE_ADMIN");

        private final String principal;
        private final String authority;

        ControlPlaneRole(String principal, String authority) {
            this.principal = principal;
            this.authority = authority;
        }

        public String principal() {
            return principal;
        }

        public String authority() {
            return authority;
        }
    }
}
