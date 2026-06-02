package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.domain.GoodsReceipt;
import com.northwood.inventory.domain.GoodsReceiptId;
import com.northwood.inventory.domain.GoodsReceiptRepository;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.DomainEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory backing for {@link GoodsReceiptRepository}. Drains the aggregate's
 * pending events ({@code GoodsReceived}) to the outbox on save, mirroring the
 * Jdbc adapter. Sibling of {@link InMemoryShipmentRepository}.
 */
public final class InMemoryGoodsReceiptRepository implements GoodsReceiptRepository {

    private final Map<UUID, GoodsReceipt> byId = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryGoodsReceiptRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<GoodsReceipt> findById(GoodsReceiptId id) {
        return Optional.ofNullable(byId.get(id.value()));
    }

    @Override
    public List<GoodsReceipt> findAllHeaders() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void save(GoodsReceipt receipt) {
        byId.put(receipt.id().value(), receipt);
        for (DomainEvent event : receipt.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    GoodsReceipt.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event),
                    null, null, null, null
                ));
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialise " + event.eventType(), e);
            }
        }
    }
}
