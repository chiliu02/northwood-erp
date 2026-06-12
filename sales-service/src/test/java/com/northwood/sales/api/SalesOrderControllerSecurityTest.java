package com.northwood.sales.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.dto.SalesOrderView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Role-gate test for the {@code @RequireSalesManager} cancel endpoint (REQ-SEC-001) — the Demo 8
 * "cancel-order moment": a {@code sales_clerk} JWT is forbidden (403) and a {@code sales_manager}
 * JWT is allowed (200). Proves the meta-annotation → {@code @PreAuthorize} → realm-role-authority
 * chain actually denies/admits at the controller boundary. (The realm-role → {@code ROLE_*}
 * authority mapping is covered separately by {@code KeycloakRealmRoleConverterTest}.)
 */
@WebMvcTest(SalesOrderController.class)
@Import(SalesOrderControllerSecurityTest.MethodSecurity.class)
class SalesOrderControllerSecurityTest {

    /**
     * Method security is OFF in a {@code @WebMvcTest} slice by default (the production
     * {@code @EnableMethodSecurity} lives on a conditional {@code @AutoConfiguration} that this
     * slice doesn't load). Turn it on here so the {@code @PreAuthorize} on the cancel method bites.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class MethodSecurity {
        // A minimal authenticated-only chain (CSRF off — the production resource server disables it
        // too) so the slice doesn't fall back to the OAuth2 resource-server auto-config (which needs
        // an issuer-uri / JwtDecoder). The jwt() request post-processor injects the authentication
        // directly, so no real token decoding is needed; @PreAuthorize does the role check.
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .csrf(c -> c.disable());
            return http.build();
        }
    }

    private static final String CANCEL = "/api/sales-orders/{id}/cancel";
    private static final String BODY = "{\"reason\":\"Customer changed mind\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SalesOrderService service;

    @Test
    void cancel_is_forbidden_for_a_sales_clerk() throws Exception {
        mvc.perform(post(CANCEL, UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_sales_clerk")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isForbidden());
        // The gate blocks before the method body runs — the service is never touched.
        verifyNoInteractions(service);
    }

    @Test
    void cancel_is_allowed_for_a_sales_manager() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.of(view(id)));

        mvc.perform(post(CANCEL, id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_sales_manager")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isOk());
        verify(service).cancel(any());
    }

    private static SalesOrderView view(UUID id) {
        return new SalesOrderView(
            id, "SO-SEC-1", UUID.randomUUID(), "CUST-001", "Sydney Home Living",
            LocalDate.now(), null, "cancelled", "AUD",
            new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
            "on_shipment", 1L, List.of());
    }
}
