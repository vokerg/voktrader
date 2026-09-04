package com.vokerg.voktrader.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "control_plane_audit_events")
public class ControlPlaneAuditEventEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "request_method", nullable = false, updatable = false, length = 16)
    private String requestMethod;

    @Column(name = "request_path", nullable = false, updatable = false, length = 512)
    private String requestPath;

    @Column(name = "principal_name", nullable = false, updatable = false, length = 128)
    private String principalName;

    @Column(name = "authority_name", nullable = false, updatable = false, length = 64)
    private String authorityName;

    @Column(name = "remote_address", nullable = false, updatable = false, length = 128)
    private String remoteAddress;

    @Column(name = "confirmation_present", nullable = false, updatable = false)
    private boolean confirmationPresent;

    protected ControlPlaneAuditEventEntity() {
    }

    private ControlPlaneAuditEventEntity(
            UUID id,
            Instant occurredAt,
            String requestMethod,
            String requestPath,
            String principalName,
            String authorityName,
            String remoteAddress,
            boolean confirmationPresent
    ) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.requestMethod = requestMethod;
        this.requestPath = requestPath;
        this.principalName = principalName;
        this.authorityName = authorityName;
        this.remoteAddress = remoteAddress;
        this.confirmationPresent = confirmationPresent;
    }

    public static ControlPlaneAuditEventEntity attempt(
            Instant occurredAt,
            String requestMethod,
            String requestPath,
            String principalName,
            String authorityName,
            String remoteAddress,
            boolean confirmationPresent
    ) {
        return new ControlPlaneAuditEventEntity(
                UUID.randomUUID(),
                occurredAt,
                requestMethod,
                requestPath,
                principalName,
                authorityName,
                remoteAddress,
                confirmationPresent
        );
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getPrincipalName() {
        return principalName;
    }

    public String getAuthorityName() {
        return authorityName;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public boolean isConfirmationPresent() {
        return confirmationPresent;
    }
}
