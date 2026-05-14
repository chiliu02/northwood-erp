package com.northwood.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret bypass for the technical-demo SPA's BFF. The demo SPA has no
 * login UI by design (it's a Saga Console / event drawer for storytelling, not
 * a per-user app), so its BFF cannot relay a Keycloak Bearer token the way
 * erp-web-ui-bff does. This filter is the documented hole: if the request
 * carries header {@code X-Northwood-Demo-Bypass} matching the configured
 * token, we install a synthetic authentication granted every business role,
 * so {@code .anyRequest().authenticated()} passes and any future
 * {@code @PreAuthorize("hasRole(...)")} also passes.
 *
 * <p>Disabled when the configured token is blank — production environments
 * leave the property unset and the filter is a no-op.
 *
 * <p>Authority list mirrors the 13 realm roles in the locked security plan
 * (7 personas + 5 manager tiers + auditor; sysadmin omitted — Keycloak admin
 * only, no business permissions).
 */
public class DemoBypassAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Northwood-Demo-Bypass";
    public static final String PRINCIPAL = "demo-bypass";

    private static final List<GrantedAuthority> AUTHORITIES = List.of(
        new SimpleGrantedAuthority("ROLE_catalog_manager"),
        new SimpleGrantedAuthority("ROLE_sales_clerk"),
        new SimpleGrantedAuthority("ROLE_warehouse_clerk"),
        new SimpleGrantedAuthority("ROLE_production_planner"),
        new SimpleGrantedAuthority("ROLE_purchasing_clerk"),
        new SimpleGrantedAuthority("ROLE_accountant"),
        new SimpleGrantedAuthority("ROLE_sales_manager"),
        new SimpleGrantedAuthority("ROLE_warehouse_manager"),
        new SimpleGrantedAuthority("ROLE_production_supervisor"),
        new SimpleGrantedAuthority("ROLE_purchasing_manager"),
        new SimpleGrantedAuthority("ROLE_finance_manager"),
        new SimpleGrantedAuthority("ROLE_auditor")
    );

    private final String expectedToken;

    public DemoBypassAuthenticationFilter(String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    public boolean isEnabled() {
        return !expectedToken.isBlank();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        if (isEnabled()) {
            String header = request.getHeader(HEADER);
            if (header != null && header.equals(expectedToken)) {
                // Spring Security 6+ pattern: createEmptyContext + setContext.
                // Mutating the existing context via getContext().setAuthentication
                // doesn't reliably propagate to AOP @PreAuthorize checks because
                // the existing context can be a DeferredSecurityContext wrapper —
                // POSTs with @PreAuthorize then 401 with the Bearer challenge
                // (BearerTokenAccessDeniedHandler returns 401, not 403, for
                // AccessDeniedException) even though .anyRequest().authenticated()
                // passed for the same auth on GETs without @PreAuthorize.
                var auth = new UsernamePasswordAuthenticationToken(PRINCIPAL, null, AUTHORITIES);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
