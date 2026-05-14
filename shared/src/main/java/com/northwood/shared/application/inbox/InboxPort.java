package com.northwood.shared.application.inbox;

import java.util.UUID;

/**
 * Port through which an idempotent consumer reads/writes the service's inbox.
 */
public interface InboxPort {

    boolean alreadyProcessed(UUID messageId, String consumerName);

    void recordProcessed(InboxRow row);
}
