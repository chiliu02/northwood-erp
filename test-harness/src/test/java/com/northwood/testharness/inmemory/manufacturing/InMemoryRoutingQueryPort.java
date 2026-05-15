package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.RoutingQueryPort;
import com.northwood.manufacturing.domain.Routing;
import com.northwood.manufacturing.domain.RoutingId;
import com.northwood.manufacturing.domain.RoutingOperation;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link RoutingQueryPort}. Seedable per finished product;
 * {@link #put(UUID, RoutingOperation...)} builds a single-version active
 * routing with the supplied operations.
 */
public final class InMemoryRoutingQueryPort implements RoutingQueryPort {

    private final Map<UUID, Routing> byProductId = new HashMap<>();

    public InMemoryRoutingQueryPort put(UUID finishedProductId, RoutingOperation... operations) {
        Routing r = new Routing(
            RoutingId.of(UUID.randomUUID()),
            finishedProductId,
            "1",
            "active",
            List.of(operations)
        );
        byProductId.put(finishedProductId, r);
        return this;
    }

    /**
     * Convenience: seed a single-op routing using a synthetic work centre.
     * The harness doesn't model work-centre semantics today.
     */
    public InMemoryRoutingQueryPort putSingleOp(UUID finishedProductId) {
        return put(finishedProductId, new RoutingOperation(
            UUID.randomUUID(),
            10,
            "OP-10",
            "Assemble",
            UUID.randomUUID(),
            BigDecimal.valueOf(15),
            BigDecimal.valueOf(60)
        ));
    }

    @Override
    public Optional<Routing> findActiveByFinishedProductId(UUID finishedProductId) {
        return Optional.ofNullable(byProductId.get(finishedProductId));
    }
}
