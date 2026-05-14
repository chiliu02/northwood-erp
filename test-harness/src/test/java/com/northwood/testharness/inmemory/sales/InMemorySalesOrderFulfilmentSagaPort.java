package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaPort;
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
 * In-memory {@link SalesOrderFulfilmentSagaPort}. Single-threaded harness
 * variant — production has a {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * concurrency model that doesn't apply here.
 *
 * <p>{@link #claimDue} returns sagas whose {@code state} is in
 * {@code activeStates} and whose {@code nextRetryAt} is in the past, ordered
 * by {@code nextRetryAt} ascending. Each claimed row is stamped with
 * {@code leaseOwner} + {@code leaseExpiresAt} mirroring the JDBC adapter
 * behaviour.
 */
public final class InMemorySalesOrderFulfilmentSagaPort implements SalesOrderFulfilmentSagaPort {

    private final Map<UUID, SalesOrderFulfilmentSaga> bySagaId = new HashMap<>();

    @Override
    public List<SalesOrderFulfilmentSaga> claimDue(int batchSize, Set<String> activeStates,
                                                   String leaseOwner, Duration leaseTtl) {
        Instant now = Instant.now();
        List<SalesOrderFulfilmentSaga> due = new ArrayList<>();
        for (SalesOrderFulfilmentSaga s : bySagaId.values()) {
            if (!activeStates.contains(s.state())) continue;
            if (s.nextRetryAt() != null && s.nextRetryAt().isAfter(now)) continue;
            if (s.leaseExpiresAt() != null && s.leaseExpiresAt().isAfter(now)) continue;
            due.add(s);
        }
        due.sort(Comparator.comparing(
            SalesOrderFulfilmentSaga::nextRetryAt,
            Comparator.nullsFirst(Comparator.naturalOrder())
        ));
        if (due.size() > batchSize) {
            due = new ArrayList<>(due.subList(0, batchSize));
        }
        Instant leaseExpiresAt = now.plus(leaseTtl);
        for (SalesOrderFulfilmentSaga s : due) {
            s.acquireLease(leaseOwner, leaseExpiresAt);
        }
        return due;
    }

    @Override
    public void save(SalesOrderFulfilmentSaga saga) {
        saga.incrementVersion();
        bySagaId.put(saga.sagaId(), saga);
    }

    @Override
    public void insert(SalesOrderFulfilmentSaga saga) {
        bySagaId.put(saga.sagaId(), saga);
    }

    @Override
    public Optional<SalesOrderFulfilmentSaga> findBySagaId(UUID sagaId) {
        return Optional.ofNullable(bySagaId.get(sagaId));
    }

    @Override
    public Optional<SalesOrderFulfilmentSaga> findBySalesOrderId(UUID salesOrderId) {
        return bySagaId.values().stream()
            .filter(s -> salesOrderId.equals(s.salesOrderId()))
            .findFirst();
    }
}
