package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.domain.Bom;
import com.northwood.manufacturing.domain.BomId;
import com.northwood.manufacturing.domain.BomLine;
import com.northwood.manufacturing.domain.BomLineId;
import com.northwood.manufacturing.domain.BomRepository;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.DomainEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory {@link BomRepository}. Drains the aggregate's pending events to
 * the outbox on save, mirroring the JDBC adapter. Enforces "at most one
 * active per finished_product_id" at save time so the harness reproduces the
 * production partial-unique-index behaviour.
 */
public final class InMemoryBomRepository implements BomRepository {

    private final Map<UUID, BomState> bomsById = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryBomRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<Bom> findById(BomId id) {
        BomState state = bomsById.get(id.value());
        return Optional.ofNullable(state).map(BomState::toAggregate);
    }

    @Override
    public Optional<BomId> findBomIdByLineId(BomLineId bomLineId) {
        for (BomState state : bomsById.values()) {
            for (BomLine line : state.lines) {
                if (line.id().value().equals(bomLineId.value())) {
                    return Optional.of(BomId.of(state.id));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void save(Bom bom) {
        BomState state = bomsById.get(bom.id().value());
        if (state == null) {
            state = new BomState(
                bom.id().value(),
                bom.finishedProductId(),
                bom.finishedProductSku(),
                bom.finishedProductName(),
                bom.version(),
                bom.status(),
                new ArrayList<>(bom.lines()),
                1L
            );
            bomsById.put(bom.id().value(), state);
            // Drain added lines (already on the aggregate copy used above; the
            // tracking lists are consumed below).
            bom.pullAddedLines();
            bom.pullRemovedLineIds();
        } else {
            // Enforce uq_bom_active_per_product: any OTHER active for the same
            // finished product makes activation fail.
            if (bom.status() == Bom.Status.ACTIVE && state.status != Bom.Status.ACTIVE) {
                for (BomState other : bomsById.values()) {
                    if (!other.id.equals(state.id)
                        && other.finishedProductId.equals(state.finishedProductId)
                        && other.status == Bom.Status.ACTIVE) {
                        throw new IllegalStateException(
                            "another bom_header is already active for product " + state.finishedProductId
                        );
                    }
                }
            }
            state.status = bom.status();
            state.rowVersion = state.rowVersion + 1;
            // Apply line diff.
            Collection<BomLineId> removedIds = bom.pullRemovedLineIds();
            for (BomLineId rid : removedIds) {
                state.lines.removeIf(line -> line.id().value().equals(rid.value()));
            }
            for (BomLine added : bom.pullAddedLines()) {
                state.lines.add(added);
            }
        }
        for (DomainEvent event : bom.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    Bom.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event),
                    null, null, null, null
                ));
            } catch (JacksonException e) {
                throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
            }
        }
    }

    private static final class BomState {
        final UUID id;
        final UUID finishedProductId;
        final String finishedProductSku;
        final String finishedProductName;
        final String version;
        Bom.Status status;
        final List<BomLine> lines;
        long rowVersion;

        BomState(
            UUID id, UUID finishedProductId,
            String finishedProductSku, String finishedProductName,
            String version, Bom.Status status,
            List<BomLine> lines, long rowVersion
        ) {
            this.id = id;
            this.finishedProductId = finishedProductId;
            this.finishedProductSku = finishedProductSku;
            this.finishedProductName = finishedProductName;
            this.version = version;
            this.status = status;
            this.lines = lines;
            this.rowVersion = rowVersion;
        }

        Bom toAggregate() {
            return Bom.reconstitute(
                BomId.of(id),
                finishedProductId,
                finishedProductSku,
                finishedProductName,
                version,
                status,
                lines,
                rowVersion
            );
        }
    }
}
