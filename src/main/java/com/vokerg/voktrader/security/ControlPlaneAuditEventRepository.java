package com.vokerg.voktrader.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ControlPlaneAuditEventRepository extends JpaRepository<ControlPlaneAuditEventEntity, UUID> {
}
