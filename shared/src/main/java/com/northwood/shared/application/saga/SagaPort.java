package com.northwood.shared.application.saga;

import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-service port for reading and updating a saga state table. Each service
 * implements this against its own {@code <schema>.<flow>_saga} table.
 *
 * <p>Implementations use {@code SELECT ... FOR UPDATE SKIP LOCKED} to claim
 * due rows without contending with sibling workers, then set {@code lease_owner}
 * + {@code lease_expires_at} so the row is reserved across the lock release.
 * Writes guard on the {@code version} column for optimistic concurrency.
 */
public interface SagaPort<S extends SagaInstance> {

    /**
     * Atomically claim up to {@code batchSize} rows whose {@code saga_state} is
     * in {@code activeStates} and whose {@code next_retry_at} is due (or whose
     * lease has expired). Each returned row has {@code lease_owner} +
     * {@code lease_expires_at} set so a parallel worker won't pick it up again
     * before its lease expires.
     */
    List<S> claimDue(int batchSize, Set<String> activeStates, String leaseOwner, Duration leaseTtl);

    /**
     * Persist the saga's current state. Implementations must enforce the
     * optimistic-concurrency rule: UPDATE ... WHERE saga_id = ? AND version = ?
     * with {@code version = version + 1}; throw on zero rows affected.
     */
    void update(S saga);

    /** Insert a new saga row. */
    void insert(S saga);

    /**
     * Reload a saga by its primary key. Used by {@link SagaManager} to
     * recover the canonical state after an advance() rollback so retry
     * scheduling does not write partially-mutated in-memory state.
     */
    Optional<S> findBySagaId(UUID sagaId);
}
