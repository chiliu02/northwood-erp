package com.northwood.shared.infrastructure.audit;

import com.northwood.shared.api.audit.AuditController;
import com.northwood.shared.application.audit.AuditQueryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wires the audit-log endpoint into every service. The
 * controller queries the per-service {@code outbox_message} table — same
 * unqualified-name + search_path pattern as the inbox/outbox adapters.
 *
 * <p>Conditional on {@link RestController} so test harnesses that don't
 * boot the web stack stay unaffected.
 *
 * <p>Opt-out via {@code northwood.audit.enabled=false}. Reporting-service
 * sets this — it's inbox-only and has no {@code outbox_message} table, so
 * the controller's query would 500 with "relation outbox_message does not
 * exist" on every call from the BFF's cross-service audit aggregator.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(RestController.class)
@ConditionalOnProperty(prefix = "northwood.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditAutoConfiguration {

    @Bean
    public JdbcAuditQueryAdapter jdbcAuditQueryAdapter(
        JdbcTemplate jdbc,
        @Value("${spring.application.name:unknown}") String serviceName
    ) {
        // The service-name suffix is "-service"; strip it so the wire shape
        // is "product" / "sales" / etc., matching the existing source-service
        // header convention on EventEnvelope.
        String trimmed = serviceName.endsWith("-service")
            ? serviceName.substring(0, serviceName.length() - "-service".length())
            : serviceName;
        return new JdbcAuditQueryAdapter(jdbc, trimmed);
    }

    @Bean
    public AuditController auditController(AuditQueryPort audit) {
        return new AuditController(audit);
    }
}
