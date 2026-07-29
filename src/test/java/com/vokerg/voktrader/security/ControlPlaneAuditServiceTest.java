package com.vokerg.voktrader.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ControlPlaneAuditServiceTest {
    @Test
    void recordsAuthenticatedMutationAttemptWithoutSecretsOrRequestBody() {
        ControlPlaneAuditEventRepository repository = mock(ControlPlaneAuditEventRepository.class);
        ControlPlaneAuditService service = new ControlPlaneAuditService(repository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bots/7/pause");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(ControlPlaneProperties.AUTHORIZATION_HEADER, "Bearer secret-that-must-not-be-persisted");
        request.setContent("sensitive body".getBytes());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "control-plane-operator",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))
        );

        service.recordMutationAttempt(request, authentication, true);

        ArgumentCaptor<ControlPlaneAuditEventEntity> captor = ArgumentCaptor.forClass(ControlPlaneAuditEventEntity.class);
        verify(repository).save(captor.capture());
        ControlPlaneAuditEventEntity event = captor.getValue();
        assertThat(event.getId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getRequestMethod()).isEqualTo("POST");
        assertThat(event.getRequestPath()).isEqualTo("/api/bots/7/pause");
        assertThat(event.getPrincipalName()).isEqualTo("control-plane-operator");
        assertThat(event.getAuthorityName()).isEqualTo("ROLE_OPERATOR");
        assertThat(event.getRemoteAddress()).isEqualTo("127.0.0.1");
        assertThat(event.isConfirmationPresent()).isTrue();
        assertThat(event.toString()).doesNotContain("secret-that-must-not-be-persisted", "sensitive body");
    }

    @Test
    void recordsAnonymousMutationAttempt() {
        ControlPlaneAuditEventRepository repository = mock(ControlPlaneAuditEventRepository.class);
        ControlPlaneAuditService service = new ControlPlaneAuditService(repository);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/bots/7");

        service.recordMutationAttempt(request, null, false);

        ArgumentCaptor<ControlPlaneAuditEventEntity> captor = ArgumentCaptor.forClass(ControlPlaneAuditEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPrincipalName()).isEqualTo("anonymous");
        assertThat(captor.getValue().getAuthorityName()).isEqualTo("anonymous");
        assertThat(captor.getValue().isConfirmationPresent()).isFalse();
    }
}
