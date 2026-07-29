package com.vokerg.voktrader.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class ControlPlaneMutationGuardFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATING_METHODS = Set.of(
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name()
    );

    private final ControlPlaneProperties properties;
    private final ControlPlaneAuditService auditService;

    public ControlPlaneMutationGuardFilter(
            ControlPlaneProperties properties,
            ControlPlaneAuditService auditService
    ) {
        this.properties = properties;
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api") || path.startsWith("/api/"))
                || !MUTATING_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String confirmation = request.getHeader(ControlPlaneProperties.CONFIRMATION_HEADER);
        boolean confirmationPresent = confirmation != null && !confirmation.isBlank();

        // The audit write happens before the controller is invoked. If persistence fails,
        // the mutation fails closed and no control-plane side effect is reached.
        auditService.recordMutationAttempt(request, authentication, confirmationPresent);

        if (isAuthenticated(authentication) && !properties.confirmationMatches(confirmation)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Valid mutation confirmation is required\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
