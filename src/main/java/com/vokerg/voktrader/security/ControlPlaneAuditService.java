package com.vokerg.voktrader.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ControlPlaneAuditService {
    private final ControlPlaneAuditEventRepository repository;

    public ControlPlaneAuditService(ControlPlaneAuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMutationAttempt(
            HttpServletRequest request,
            Authentication authentication,
            boolean confirmationPresent
    ) {
        String principal = isAuthenticated(authentication) ? authentication.getName() : "anonymous";
        String authority = isAuthenticated(authentication)
                ? authentication.getAuthorities().stream().findFirst().map(Object::toString).orElse("authenticated")
                : "anonymous";
        repository.save(ControlPlaneAuditEventEntity.attempt(
                Instant.now(),
                request.getMethod(),
                request.getRequestURI(),
                principal,
                authority,
                request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr(),
                confirmationPresent
        ));
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
