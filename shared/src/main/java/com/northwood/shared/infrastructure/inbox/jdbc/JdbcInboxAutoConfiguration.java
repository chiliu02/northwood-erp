package com.northwood.shared.infrastructure.inbox.jdbc;

import com.northwood.shared.application.inbox.InboxPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers the shared {@link JdbcInboxAdapter} as the {@link InboxPort} bean
 * for any service on the classpath. Component scan starts at each service's
 * {@code @SpringBootApplication} root and would not reach
 * {@code com.northwood.shared.*}, so we publish the bean explicitly.
 *
 * <p>{@code @ConditionalOnMissingBean} lets a service register its own
 * {@link InboxPort} if it needs custom behaviour (e.g. a test double).
 *
 * <p>Producer-only services (product-service today) get the bean too but
 * never use it — no inbox handlers are wired so it sits idle.
 */
@AutoConfiguration
public class JdbcInboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InboxPort.class)
    public InboxPort inboxPort(JdbcTemplate jdbc) {
        return new JdbcInboxAdapter(jdbc);
    }
}
