package com.vokerg.voktrader.security;

import org.springframework.data.repository.Repository;

import java.util.UUID;

/**
 * Append-only application boundary: callers can insert audit events but cannot query,
 * update, or delete them through this repository contract.
 */
public interface ControlPlaneAuditEventRepository extends Repository<ControlPlaneAuditEventEntity, UUID> {
    ControlPlaneAuditEventEntity save(ControlPlaneAuditEventEntity event);
}
