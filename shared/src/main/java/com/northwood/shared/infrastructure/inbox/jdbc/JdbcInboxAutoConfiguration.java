package com.northwood.shared.infrastructure.inbox.jdbc;

import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.domain.Assert;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers the shared {@link JdbcInboxAdapter} as the {@link InboxPort} bean,
 * plus the {@link InboxDedupStrategy} it delegates to, for any service on the
 * classpath. Component scan starts at each service's
 * {@code @SpringBootApplication} root and would not reach
 * {@code com.northwood.shared.*}, so we publish the beans explicitly.
 *
 * <p>The dedup strategy is chosen by {@code northwood.inbox.dedup-strategy}:
 * <ul>
 *   <li>{@code advisory-lock} (default) — Option B, transaction-scoped
 *       PostgreSQL advisory lock; race-safe, no schema change. See
 *       {@link AdvisoryLockInboxDedupStrategy}.</li>
 *   <li>{@code unique-claim} — Option A, a unique-constraint claim; portable
 *       across other engines but needs an {@code inbox_dedup} table the
 *       partitioned {@code inbox_message} can't carry. Not wired yet (fails
 *       fast); see {@code docs/messaging.md}.</li>
 * </ul>
 *
 * <p>{@code @ConditionalOnMissingBean} lets a service register its own
 * {@link InboxPort} / {@link InboxDedupStrategy} for custom behaviour (e.g. a
 * test double).
 *
 * <p>Producer-only services (product-service today) get the beans too but
 * never use them — no inbox handlers are wired so they sit idle.
 */
@AutoConfiguration
public class JdbcInboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InboxDedupStrategy.class)
    public InboxDedupStrategy inboxDedupStrategy(
            JdbcTemplate jdbc,
            @Value("${northwood.inbox.dedup-strategy:advisory-lock}") String strategy) {
        return switch (strategy.trim().toLowerCase(Locale.ROOT)) {
            case "advisory-lock" -> new AdvisoryLockInboxDedupStrategy(jdbc);
            case "unique-claim" -> throw new IllegalStateException(
                "northwood.inbox.dedup-strategy=unique-claim is not wired yet — it needs an "
              + "inbox_dedup table (the partitioned inbox_message can't carry a "
              + "UNIQUE(message_id, consumer_name)); see docs/messaging.md. "
              + "Use advisory-lock (the default) on PostgreSQL.");
            default -> throw Assert.unknownValue("northwood.inbox.dedup-strategy", strategy);
        };
    }

    @Bean
    @ConditionalOnMissingBean(InboxPort.class)
    public InboxPort inboxPort(JdbcTemplate jdbc, InboxDedupStrategy dedup) {
        return new JdbcInboxAdapter(jdbc, dedup);
    }
}
