package com.northwood.testharness.inmemory;

import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.inbox.InboxRow;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory inbox backing one service's idempotency table. Each service
 * gets its own instance via the per-service test kit.
 */
public final class InMemoryInboxPort implements InboxPort {

    private record Key(UUID messageId, String handlerName) {}

    private final Set<Key> processed = new HashSet<>();

    @Override
    public synchronized boolean alreadyProcessed(UUID messageId, String handlerName) {
        return processed.contains(new Key(messageId, handlerName));
    }

    @Override
    public synchronized void recordProcessed(InboxRow row) {
        processed.add(new Key(row.getMessageId(), row.getHandlerName()));
    }
}
