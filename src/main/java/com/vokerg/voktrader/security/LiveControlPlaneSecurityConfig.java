package com.vokerg.voktrader.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("live")
@EnableConfigurationProperties(ControlPlaneProperties.class)
public class LiveControlPlaneSecurityConfig {
    @Bean
    InitializingBean validateLiveControlPlaneProperties(ControlPlaneProperties properties) {
        return properties::validateForLive;
    }

    @Bean
    ControlPlaneTokenAuthenticationFilter controlPlaneTokenAuthenticationFilter(
            ControlPlaneProperties properties
    ) {
        return new ControlPlaneTokenAuthenticationFilter(properties);
    }

    @Bean
    ControlPlaneMutationGuardFilter controlPlaneMutationGuardFilter(
            ControlPlaneProperties properties,
            ControlPlaneAuditService auditService
    ) {
        return new ControlPlaneMutationGuardFilter(properties, auditService);
    }

    @Bean
    AuthenticationEntryPoint controlPlaneAuthenticationEntryPoint() {
        return (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Control-plane authentication is required\"}");
        };
    }

    @Bean
    AccessDeniedHandler controlPlaneAccessDeniedHandler() {
        return (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Control-plane role is not permitted\"}");
        };
    }

    @Bean
    SecurityFilterChain liveControlPlaneSecurityFilterChain(
            HttpSecurity http,
            ControlPlaneTokenAuthenticationFilter tokenFilter,
            ControlPlaneMutationGuardFilter mutationGuardFilter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .securityMatcher("/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                // API authentication uses an explicit bearer token and never browser cookies.
                // The separate mutation confirmation header protects unsafe browser/API calls.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(
                                "/h2-console/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/admin/**"
                        ).denyAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("READ_ONLY", "OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.HEAD, "/api/**").hasAnyRole("READ_ONLY", "OPERATOR", "ADMIN")
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().denyAll()
                )
                .addFilterAfter(tokenFilter, LogoutFilter.class)
                .addFilterAfter(mutationGuardFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
