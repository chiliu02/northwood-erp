package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.domain.DomainEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory double of {@link ReplenishmentRequestRepository}. Backs the §2.35
 * Slice B detection-trigger path in the test-harness scenarios. Enforces the
 * one-open-per-(product, warehouse) invariant in memory (mirrors the
 * Postgres partial unique index — throws {@link DuplicateKeyException} on
 * conflict so the detection service's swallow path is exercised end-to-end).
 *
 * <p>Drains pending events through the same {@link OutboxAppender} the other
 * in-memory repositories use, so emitted events land on the synchronous bus.
 */
public final class InMemoryReplenishmentRequestRepository implements ReplenishmentRequestRepository {

    private final Map<UUID, ReplenishmentRequest> byId = new HashMap<>();
    private final OutboxAppender outbox;
    private final ObjectMapper json;

    public InMemoryReplenishmentRequestRepository(OutboxAppender outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<ReplenishmentRequest> findById(ReplenishmentRequestId id) {
        return Optional.ofNullable(byId.get(id.value()));
    }

    @Override
    public Optional<ReplenishmentRequest> findByDispatchedAggregateId(UUID dispatchedAggregateId) {
        for (ReplenishmentRequest r : byId.values()) {
            if (dispatchedAggregateId.equals(r.dispatchedAggregateId())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ReplenishmentRequest> findByLinkedPurchaseOrderId(UUID purchaseOrderId) {
        for (ReplenishmentRequest r : byId.values()) {
            if (purchaseOrderId.equals(r.linkedPurchaseOrderId())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    @Override
    public void save(ReplenishmentRequest r) {
        if (r.version() == 0L && !byId.containsKey(r.id().value())) {
            if (hasOpenFor(r.productId(), r.warehouseId())) {
                throw new DuplicateKeyException(
                    "in-memory uq_replenishment_request_open violation for product=" + r.productId()
                        + " warehouse=" + r.warehouseId()
                );
            }
        }
        // Slice E: updates land via mutator → save (mark dispatched / link PO /
        // mark fulfilled). The in-memory double trusts the aggregate's state
        // machine; the JdbcReplenishmentRequestRepository does an OCC version
        // check (modelled here as last-writer-wins for simplicity).
        byId.put(r.id().value(), r);
        for (DomainEvent event : r.pullPendingEvents()) {
            outbox.append(event, ReplenishmentRequest.AGGREGATE_TYPE);
        }
    }

    private boolean hasOpenFor(UUID productId, UUID warehouseId) {
        for (ReplenishmentRequest existing : byId.values()) {
            if (existing.productId().equals(productId)
                && existing.warehouseId().equals(warehouseId)
                && (existing.status() == ReplenishmentRequest.Status.REQUESTED
                    || existing.status() == ReplenishmentRequest.Status.DISPATCHED)) {
                return true;
            }
        }
        return false;
    }

    /** Test helper: list every request the kit has stored, newest-undefined order. */
    public List<ReplenishmentRequest> all() {
        return new ArrayList<>(byId.values());
    }
}
