package com.northwood.shared.infrastructure.outbox.jdbc;

import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.security.CurrentUserAccessor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

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
 * use it — they don't enable draining ({@code northwood.outbox.drain.enabled}),
 * so {@code OutboxDrainAutoConfiguration} creates no drainer/scheduler and the
 * adapter is never polled.
 */
@AutoConfiguration
public class JdbcOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxPort.class)
    public OutboxPort outboxPort(JdbcTemplate jdbc) {
        return new JdbcOutboxAdapter(jdbc);
    }

    /**
     * The single append-path seam over {@link OutboxPort} (see
     * {@link OutboxAppender}). Wired here alongside the port it wraps so every
     * service that has an outbox also gets the appender.
     */
    @Bean
    @ConditionalOnMissingBean(OutboxAppender.class)
    public OutboxAppender outboxAppender(OutboxPort outbox, ObjectMapper json, CurrentUserAccessor currentUser) {
        return new OutboxAppender(outbox, json, currentUser);
    }
}
