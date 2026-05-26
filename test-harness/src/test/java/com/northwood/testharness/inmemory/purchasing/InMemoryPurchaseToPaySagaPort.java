package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaPort;
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

public final class InMemoryPurchaseToPaySagaPort implements PurchaseToPaySagaPort {

    private final Map<UUID, PurchaseToPaySaga> bySagaId = new HashMap<>();

    @Override
    public List<PurchaseToPaySaga> claimDue(int batchSize, Set<String> activeStates,
                                            String leaseOwner, Duration leaseTtl) {
        Instant now = Instant.now();
        List<PurchaseToPaySaga> due = new ArrayList<>();
        for (PurchaseToPaySaga s : bySagaId.values()) {
            if (!activeStates.contains(s.state())) continue;
            if (s.nextRetryAt() != null && s.nextRetryAt().isAfter(now)) continue;
            if (s.leaseExpiresAt() != null && s.leaseExpiresAt().isAfter(now)) continue;
            due.add(s);
        }
        due.sort(Comparator.comparing(
            PurchaseToPaySaga::nextRetryAt,
            Comparator.nullsFirst(Comparator.naturalOrder())
        ));
        if (due.size() > batchSize) {
            due = new ArrayList<>(due.subList(0, batchSize));
        }
        Instant leaseExpiresAt = now.plus(leaseTtl);
        for (PurchaseToPaySaga s : due) {
            s.acquireLease(leaseOwner, leaseExpiresAt);
        }
        return due;
    }

    @Override
    public void update(PurchaseToPaySaga saga) {
        saga.incrementVersion();
        bySagaId.put(saga.sagaId(), saga);
    }

    @Override
    public void insert(PurchaseToPaySaga saga) {
        bySagaId.put(saga.sagaId(), saga);
    }

    @Override
    public Optional<PurchaseToPaySaga> findBySagaId(UUID sagaId) {
        return Optional.ofNullable(bySagaId.get(sagaId));
    }

    @Override
    public Optional<PurchaseToPaySaga> findByPurchaseOrderId(UUID purchaseOrderHeaderId) {
        return bySagaId.values().stream()
            .filter(s -> purchaseOrderHeaderId.equals(s.purchaseOrderHeaderId()))
            .findFirst();
    }
}
