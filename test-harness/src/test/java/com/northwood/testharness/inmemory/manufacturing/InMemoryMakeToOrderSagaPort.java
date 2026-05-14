package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link MakeToOrderSagaPort}. Same single-threaded harness
 * variant as the sales port: claim returns due rows, stamps the lease, and
 * the abstract base's per-saga txn just calls {@code save} which bumps
 * version.
 */
public final class InMemoryMakeToOrderSagaPort implements MakeToOrderSagaPort {

    private final Map<UUID, MakeToOrderSaga> bySagaId = new HashMap<>();

    @Override
    public List<MakeToOrderSaga> claimDue(int batchSize, Set<String> activeStates,
                                          String leaseOwner, Duration leaseTtl) {
        Instant now = Instant.now();
        List<MakeToOrderSaga> due = new ArrayList<>();
        for (MakeToOrderSaga s : bySagaId.values()) {
            if (!activeStates.contains(s.state())) continue;
            if (s.nextRetryAt() != null && s.nextRetryAt().isAfter(now)) continue;
            if (s.leaseExpiresAt() != null && s.leaseExpiresAt().isAfter(now)) continue;
            due.add(s);
        }
        due.sort(Comparator.comparing(
            MakeToOrderSaga::nextRetryAt,
            Comparator.nullsFirst(Comparator.naturalOrder())
        ));
        if (due.size() > batchSize) {
            due = new ArrayList<>(due.subList(0, batchSize));
        }
        Instant leaseExpiresAt = now.plus(leaseTtl);
        for (MakeToOrderSaga s : due) {
            s.acquireLease(leaseOwner, leaseExpiresAt);
        }
        return due;
    }

    @Override
    public void save(MakeToOrderSaga saga) {
        saga.incrementVersion();
        bySagaId.put(saga.sagaId(), saga);
    }

    @Override
    public void insert(MakeToOrderSaga saga) {
        bySagaId.put(saga.sagaId(), saga);
    }

    @Override
    public Optional<MakeToOrderSaga> findBySagaId(UUID sagaId) {
        return Optional.ofNullable(bySagaId.get(sagaId));
    }

    @Override
    public Optional<MakeToOrderSaga> findByWorkOrderId(UUID workOrderId) {
        return bySagaId.values().stream()
            .filter(s -> workOrderId.equals(s.workOrderId()))
            .findFirst();
    }

    /** Test-side helper: enumerate all sagas. */
    public List<MakeToOrderSaga> all() {
        return new ArrayList<>(bySagaId.values());
    }
}
