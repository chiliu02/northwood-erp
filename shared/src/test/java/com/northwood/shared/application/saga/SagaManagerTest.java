package com.northwood.shared.application.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for the base {@link SagaManager#drain} loop — specifically its
 * transient-failure → retry → recover path, which is the saga-side
 * auto-recovery the disaster-recovery section in {@code docs/messaging.md}
 * claims ("Saga step transient failure → `scheduleRetry` → re-claimed once
 * `next_retry_at <= now()`").
 *
 * <p>Drives {@code drain(...)} directly on a plain (non-proxied) instance —
 * the proxy behaviour is covered separately by {@code SagaManagerProxyTest}.
 * The harness mirrors that test: a minimal {@link TestSaga}, an in-memory
 * {@link SagaPort} that faithfully reproduces the {@code claimDue} gate
 * (active state + due + lease free/expired) so the second drain re-claims the
 * backed-off row, and a no-op {@link PlatformTransactionManager} (the advance
 * functions here mutate only in-memory state, so there is nothing to roll
 * back at the DB level).
 */
class SagaManagerTest {

    @Test
    void transient_advance_failure_is_recorded_as_a_retry_then_recovers_on_the_next_drain() {
        UUID sagaId = UUID.randomUUID();
        InMemoryTestSagaPort port = new InMemoryTestSagaPort();
        port.insert(new TestSaga(sagaId));
        // retryBackoff = ZERO so the backed-off saga is immediately due on drain 2.
        TestSagaManager manager = new TestSagaManager(port, noOpTransactionManager(), Duration.ZERO);

        AtomicInteger attempts = new AtomicInteger();
        Consumer<TestSaga> advance = saga -> {
            if (attempts.getAndIncrement() == 0) {
                throw new RuntimeException("transient downstream failure");
            }
            saga.transitionTo("done", "complete");
        };

        // Drain 1 — advance throws → caught; the manager records the retry
        // (retry_count++, next_retry_at, last_error), releases the lease, and
        // does NOT advance the saga.
        manager.drain(10, "worker-1", advance);

        TestSaga afterFailure = port.findBySagaId(sagaId).orElseThrow();
        assertThat(afterFailure.retryCount()).isEqualTo(1);
        assertThat(afterFailure.lastError()).contains("transient downstream failure");
        assertThat(afterFailure.leaseOwner()).isNull();
        assertThat(afterFailure.state()).isEqualTo("started");

        // Drain 2 — immediately due again (zero backoff); advance succeeds →
        // saga reaches its terminal state and the retry metadata is cleared.
        // No operator action between the two drains.
        manager.drain(10, "worker-1", advance);

        TestSaga afterRecovery = port.findBySagaId(sagaId).orElseThrow();
        assertThat(afterRecovery.state()).isEqualTo("done");
        assertThat(afterRecovery.retryCount()).isZero();
        assertThat(afterRecovery.lastError()).isNull();
        assertThat(afterRecovery.leaseOwner()).isNull();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void clean_advance_completes_without_recording_a_retry() {
        UUID sagaId = UUID.randomUUID();
        InMemoryTestSagaPort port = new InMemoryTestSagaPort();
        port.insert(new TestSaga(sagaId));
        TestSagaManager manager = new TestSagaManager(port, noOpTransactionManager(), Duration.ofSeconds(15));

        manager.drain(10, "worker-1", saga -> saga.transitionTo("done", "complete"));

        TestSaga saga = port.findBySagaId(sagaId).orElseThrow();
        assertThat(saga.state()).isEqualTo("done");
        assertThat(saga.retryCount()).isZero();
        assertThat(saga.lastError()).isNull();
        assertThat(saga.leaseOwner()).isNull();
    }

    // ============================================================
    // Test fixtures
    // ============================================================

    /** Minimal saga: starts in {@code started}, terminal at {@code done}/{@code failed}. */
    static final class TestSaga extends SagaInstance {
        TestSaga(UUID sagaId) {
            super(
                sagaId, "started", "init", null,
                0, Instant.now(),
                null, null,
                0L, "{}",
                Instant.now(), Instant.now(), null
            );
        }

        @Override public Set<String> terminalStates() { return Set.of("done", "failed"); }
    }

    /**
     * In-memory {@link SagaPort} holding a single saga. {@code claimDue}
     * reproduces the SQL gate — active state, due ({@code next_retry_at <=
     * now}), and lease free or expired — and stamps the lease, so the
     * backed-off saga is re-claimable on a later drain exactly as the JDBC
     * adapter would.
     */
    static final class InMemoryTestSagaPort implements SagaPort<TestSaga> {
        private TestSaga saga;

        @Override
        public List<TestSaga> claimDue(int batchSize, Set<String> activeStates, String leaseOwner, Duration leaseTtl) {
            if (saga == null || !activeStates.contains(saga.state())) {
                return List.of();
            }
            boolean due = !saga.nextRetryAt().isAfter(Instant.now());
            boolean leaseFree = saga.leaseOwner() == null
                || (saga.leaseExpiresAt() != null && saga.leaseExpiresAt().isBefore(Instant.now()));
            if (due && leaseFree) {
                saga.acquireLease(leaseOwner, Instant.now().plus(leaseTtl));
                return List.of(saga);
            }
            return List.of();
        }

        @Override public void update(TestSaga saga) { /* same instance — persisted in place */ }

        @Override public void insert(TestSaga saga) { this.saga = saga; }

        @Override
        public Optional<TestSaga> findBySagaId(UUID sagaId) {
            return saga != null && saga.sagaId().equals(sagaId) ? Optional.of(saga) : Optional.empty();
        }
    }

    static final class TestSagaManager extends SagaManager<TestSaga, SagaPort<TestSaga>> {
        TestSagaManager(SagaPort<TestSaga> sagaPort, PlatformTransactionManager tm, Duration retryBackoff) {
            super(sagaPort, tm, Duration.ofSeconds(30), retryBackoff);
        }

        @Override protected Set<String> activeStates() { return Set.of("started"); }
    }

    /** Runs the callback inline and propagates exceptions; no real tx semantics needed here. */
    private static PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition d) throws TransactionException {
                return new SimpleTransactionStatus(true);
            }
            @Override public void commit(TransactionStatus status) throws TransactionException {}
            @Override public void rollback(TransactionStatus status) throws TransactionException {}
        };
    }
}
