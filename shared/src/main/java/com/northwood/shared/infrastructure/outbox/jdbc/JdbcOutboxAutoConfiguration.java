package com.northwood.shared.infrastructure.outbox.jdbc;

import com.northwood.shared.application.outbox.OutboxPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers the shared {@link JdbcOutboxAdapter} as the {@link OutboxPort}
 * bean for any service on the classpath. Component scan starts at each
 * service's {@code @SpringBootApplication} root and would not reach
 * {@code com.northwood.shared.*}, so we publish the bean explicitly.
 *
 * <p>{@code @ConditionalOnMissingBean} lets a service register its own
 * {@link OutboxPort} if it needs custom behaviour (e.g. a test double).
 *
 * <p>Inbox-only services (reporting-service today) get the bean too but never
 * use it — there is no {@code <Service>OutboxConfig} registering an
 * {@link OutboxPublisher}, so the adapter is never polled.
 */
@AutoConfiguration
public class JdbcOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxPort.class)
    public OutboxPort outboxPort(JdbcTemplate jdbc) {
        return new JdbcOutboxAdapter(jdbc);
    }
}
