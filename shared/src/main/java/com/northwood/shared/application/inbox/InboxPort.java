package com.northwood.shared.application.inbox;

import java.util.UUID;

/**
 * Port through which a handler reads/writes the service's inbox — the mechanism
 * that makes each handler <strong>idempotent</strong>. A handler calls
 * {@link #alreadyProcessed} before applying an event and {@link #recordProcessed}
 * after, both keyed on {@code (message_id, handler_name)} and committed in the
 * same transaction as the side effects. So each event is applied exactly once
 * <em>per handler</em>: a redelivery of an already-recorded
 * {@code (message_id, handler_name)} is skipped, and the N handlers subscribed to
 * one event type each dedup independently.
 */
public interface InboxPort {

    /** True iff this handler has already recorded {@code (messageId, handlerName)} as processed. */
    boolean alreadyProcessed(UUID messageId, String handlerName);

    /** Record {@code (message_id, handler_name)} as processed, in the same transaction as the handler's side effects. */
    void recordProcessed(InboxRow row);
}
