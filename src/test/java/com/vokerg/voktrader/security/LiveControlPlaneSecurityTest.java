package com.vokerg.voktrader.security;

import com.vokerg.voktrader.api.bot.BotApiController;
import com.vokerg.voktrader.api.bot.BotApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = BotApiController.class,
        properties = {
                "voktrader.control-plane.read-only-token=readonly-token-000000000000000001",
                "voktrader.control-plane.operator-token=operator-token-000000000000000001",
                "voktrader.control-plane.admin-token=administrator-token-00000000000000001",
                "voktrader.control-plane.confirmation-token=confirmation-token-000000000000001"
        }
)
@ActiveProfiles("live")
@Import({LiveControlPlaneSecurityConfig.class, LiveControlPlaneSecurityTest.SecurityTestBeans.class})
class LiveControlPlaneSecurityTest {
    private static final String READ_ONLY_TOKEN = "readonly-token-000000000000000001";
    private static final String OPERATOR_TOKEN = "operator-token-000000000000000001";
    private static final String ADMIN_TOKEN = "administrator-token-00000000000000001";
    private static final String CONFIRMATION_TOKEN = "confirmation-token-000000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BotApiService botApiService;

    @Autowired
    private ControlPlaneAuditService auditService;

    @BeforeEach
    void resetState() {
        reset(botApiService, auditService);
        when(botApiService.killAll()).thenReturn(List.of());
        when(botApiService.list(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()
        )).thenReturn(List.of());
    }

    @Test
    void anonymousMutationIsUnauthorizedAndDoesNotReachController() throws Exception {
        mockMvc.perform(post("/api/bots/kill-all"))
                .andExpect(status().isUnauthorized());

        verify(botApiService, never()).killAll();
        verify(auditService).recordMutationAttempt(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(false)
        );
    }

    @Test
    void readOnlyCredentialCanReadButCannotMutate() throws Exception {
        mockMvc.perform(get("/api/bots").header(HttpHeaders.AUTHORIZATION, bearer(READ_ONLY_TOKEN)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/bots/kill-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READ_ONLY_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isForbidden());

        verify(botApiService, never()).killAll();
    }

    @Test
    void operatorMutationRequiresSeparateConfirmation() throws Exception {
        mockMvc.perform(post("/api/bots/kill-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR_TOKEN)))
                .andExpect(status().isForbidden());

        verify(botApiService, never()).killAll();
        verify(auditService).recordMutationAttempt(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(false)
        );
    }

    @Test
    void operatorCanPerformConfirmedControlMutation() throws Exception {
        mockMvc.perform(post("/api/bots/kill-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isOk());

        verify(botApiService).killAll();
        verify(auditService).recordMutationAttempt(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(true)
        );
    }

    @Test
    void deletePolicyRequiresAdminBeforeRouteResolution() throws Exception {
        mockMvc.perform(delete("/api/bots/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/bots/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anonymousReadIsUnauthorizedAndNonApiAdminSurfacesStayDenied() throws Exception {
        mockMvc.perform(get("/api/bots"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/h2-console/"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/admin/"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(auditService);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestBeans {
        @Bean
        BotApiService botApiService() {
            return mock(BotApiService.class);
        }

        @Bean
        ControlPlaneAuditService controlPlaneAuditService() {
            return mock(ControlPlaneAuditService.class);
        }
    }
}
