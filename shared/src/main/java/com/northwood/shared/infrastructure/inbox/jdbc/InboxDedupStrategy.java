package com.northwood.shared.infrastructure.inbox.jdbc;

import java.util.UUID;

/**
 * Infrastructure SPI for the inbox idempotency mechanism — the dedup "gate"
 * that {@link JdbcInboxAdapter#alreadyProcessed} delegates to. Selected by
 * {@code northwood.inbox.dedup-strategy} (default {@code advisory-lock}) in
 * {@link JdbcInboxAutoConfiguration}.
 *
 * <p>This seam exists so the <em>mechanism</em> (a PostgreSQL advisory lock vs a
 * unique-constraint claim) never leaks into the application layer: an inbox
 * handler ({@code AbstractInboxHandler}) only ever sees {@code InboxPort} and
 * its {@code alreadyProcessed} / {@code recordProcessed} <em>intent</em>, not
 * which strategy is in force.
 *
 * <p>An implementation does two things in one call, inside the consumer's
 * {@code @Transactional} boundary: report whether
 * {@code (messageId, consumerName)} was already processed, AND guard the
 * caller's about-to-run processing against a concurrent duplicate (a redelivery
 * landing on a second thread during a consumer-group rebalance). The shared
 * audit/dedup row is written separately by
 * {@link JdbcInboxAdapter#recordProcessed}.
 *
 * <p>Full design + the two strategies (advisory-lock vs unique-claim) and why
 * the lock is a separate statement before the check: {@code docs/messaging.md}.
 */
public interface InboxDedupStrategy {

    boolean alreadyProcessed(UUID messageId, String consumerName);
}
