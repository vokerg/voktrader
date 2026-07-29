package com.vokerg.voktrader.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        properties = {
                "voktrader.control-plane.read-only-token=readonly-token-000000000000000001",
                "voktrader.control-plane.operator-token=operator-token-000000000000000001",
                "voktrader.control-plane.admin-token=administrator-token-00000000000001",
                "voktrader.control-plane.confirmation-token=confirmation-token-000000000000001"
        }
)
@ActiveProfiles("live")
@Import({
        LiveControlPlaneSecurityConfig.class,
        LiveControlPlaneSecurityTest.ProbeController.class,
        LiveControlPlaneSecurityTest.SecurityTestBeans.class
})
class LiveControlPlaneSecurityTest {
    private static final String READ_ONLY_TOKEN = "readonly-token-000000000000000001";
    private static final String OPERATOR_TOKEN = "operator-token-000000000000000001";
    private static final String ADMIN_TOKEN = "administrator-token-00000000000001";
    private static final String CONFIRMATION_TOKEN = "confirmation-token-000000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AtomicInteger mutationCount;

    @Autowired
    private ControlPlaneAuditService auditService;

    @BeforeEach
    void resetState() {
        mutationCount.set(0);
        reset(auditService);
    }

    @Test
    void anonymousMutationIsUnauthorizedAndDoesNotReachController() throws Exception {
        mockMvc.perform(post("/api/probe").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        assertThat(mutationCount).hasValue(0);
        verify(auditService).recordMutationAttempt(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(false)
        );
    }

    @Test
    void readOnlyCredentialCanReadButCannotMutate() throws Exception {
        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, bearer(READ_ONLY_TOKEN)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READ_ONLY_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(mutationCount).hasValue(0);
    }

    @Test
    void operatorMutationRequiresSeparateConfirmation() throws Exception {
        mockMvc.perform(post("/api/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR_TOKEN)))
                .andExpect(status().isForbidden());

        assertThat(mutationCount).hasValue(0);
        verify(auditService).recordMutationAttempt(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(false)
        );
    }

    @Test
    void operatorCanPerformConfirmedNonDestructiveMutation() throws Exception {
        mockMvc.perform(post("/api/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isOk());

        assertThat(mutationCount).hasValue(1);
        verify(auditService).recordMutationAttempt(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(true)
        );
    }

    @Test
    void destructiveDeleteRequiresAdminRole() throws Exception {
        mockMvc.perform(delete("/api/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearer(OPERATOR_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/probe")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .header(ControlPlaneProperties.CONFIRMATION_HEADER, CONFIRMATION_TOKEN))
                .andExpect(status().isNoContent());

        assertThat(mutationCount).hasValue(1);
    }

    @Test
    void anonymousReadIsUnauthorizedAndNonApiAdminSurfacesStayDenied() throws Exception {
        mockMvc.perform(get("/api/probe"))
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

    @RestController
    @RequestMapping("/api/probe")
    public static class ProbeController {
        private final AtomicInteger mutationCount;

        public ProbeController(AtomicInteger mutationCount) {
            this.mutationCount = mutationCount;
        }

        @GetMapping
        public String read() {
            return "ok";
        }

        @PostMapping
        public String mutate() {
            mutationCount.incrementAndGet();
            return "mutated";
        }

        @DeleteMapping
        public void delete() {
            mutationCount.incrementAndGet();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class SecurityTestBeans {
        @Bean
        AtomicInteger mutationCount() {
            return new AtomicInteger();
        }

        @Bean
        ControlPlaneAuditService controlPlaneAuditService() {
            return mock(ControlPlaneAuditService.class);
        }
    }
}
