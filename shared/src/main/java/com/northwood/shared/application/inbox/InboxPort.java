package com.northwood.shared.application.inbox;

import java.util.UUID;

/**
 * Port through which a handler reads/writes the service's inbox.
 */
public interface InboxPort {

    boolean alreadyProcessed(UUID messageId, String handlerName);

    void recordProcessed(InboxRow row);
}
