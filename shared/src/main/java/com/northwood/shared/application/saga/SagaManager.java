package com.northwood.shared.application.saga;

import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling-style saga manager base. Subclasses implement {@link #activeStates()}
 * (which states the polling driver should pick up). The advance step itself is
 * passed in by the caller as a {@link Consumer} so the manager stays
 * saga-state-only — side effects (event emission, projection writes, service
 * calls) live with the caller (typically the {@code *SagaWorker} shell).
 *
 * <p>Two type parameters:
 * <ul>
 *   <li>{@code S} — the concrete {@link SagaInstance} subtype (e.g.
 *       {@code SalesOrderFulfilmentSaga}).</li>
 *   <li>{@code P} — the concrete {@link SagaPort} subtype (e.g.
 *       {@code SalesOrderFulfilmentSagaPort}). Carrying the port type as a
 *       parameter lets subclasses reach saga-specific port methods (e.g.
 *       {@code findBySalesOrderId}) via the inherited {@link #sagaPort} field
 *       without redeclaring it.</li>
 * </ul>
 *
 * <p>Each saga is processed in its own transaction so that an exception in
 * one saga's advance rolls back only that saga's outbox writes — sibling sagas
 * already advanced in this batch stay committed. On exception, a second,
 * fresh transaction records the retry/error metadata so the failure trail is
 * durable.
 *
 * <p>Identity is the worker's concern, not the manager's: callers pass a
 * {@code workerId} into {@link #drain} which the lease-claim SQL stamps into
 * {@code lease_owner}. A typical worker id is built once on the worker shell as
 * {@code "<saga-name>-worker@" + jvmRuntimeName} so multi-replica deployments
 * get a different id per pod.
 *
 * <p>The {@code @Scheduled} annotation lives on the worker shell — it owns the
 * cadence. This base class deliberately stays Spring-annotation-free so it can
 * be unit-tested without a context.
 */
public abstract class SagaManager<S extends SagaInstance, P extends SagaPort<S>> {

    /**
     * Inheritable logger — initialised at construction time via
     * {@code getClass()} so log lines emitted from this base class still
     * report under the concrete subclass name (e.g.
     * {@code JdbcSalesOrderFulfilmentSagaManager}). Subclasses that need
     * their own log calls can use this field directly; declaring a separate
     * {@code private static final Logger log} in the subclass is unnecessary
     * and shadows this field.
     */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Saga row port. Typed as the concrete subtype {@code P} so subclasses
     * can call saga-specific methods (e.g.
     * {@code SalesOrderFulfilmentSagaPort.findBySalesOrderId}) directly
     * without redeclaring the field.
     */
    protected final P sagaPort;

    private final TransactionTemplate tx;
    private final Duration leaseTtl;
    private final Duration retryBackoff;

    protected SagaManager(
        P sagaPort,
        PlatformTransactionManager transactionManager,
        Duration leaseTtl,
        Duration retryBackoff
    ) {
        this.sagaPort = sagaPort;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx = template;
        this.leaseTtl = leaseTtl;
        this.retryBackoff = retryBackoff;
    }

    /** States the polling driver should pick up; rows in any other state are left alone. */
    protected abstract Set<String> activeStates();

    /**
     * Drain due sagas. Each saga is processed in its own transaction. The
     * {@code workerId} is stamped into {@code lease_owner} on each claimed
     * row. The {@code advanceFn} is the per-saga advance step — caller decides
     * what mutating + emitting + projecting happens; this base just guarantees
     * per-saga transactional isolation, lease release, and retry-on-failure.
     *
     * <p>Deliberately <strong>not</strong> {@code final}, even though the
     * "single source of truth for the polling shape" intent suggests it should
     * be. Concrete subclasses are annotated with {@code @Transactional} on
     * sibling lifecycle/apply methods, so Spring creates a CGLIB proxy for the
     * whole class. CGLIB cannot override final methods; Objenesis instantiates
     * the proxy without invoking the constructor, so the proxy's
     * {@link #tx}/{@link #sagaPort}/etc. fields are null. A final {@code drain}
     * would execute on the proxy with those null fields and NPE on the first
     * field access. Leaving it non-final lets CGLIB generate an override that
     * delegates to the constructor-initialised target instance. Subclasses
     * still don't override it by convention.
     */
    public void drain(int batchSize, String workerId, Consumer<S> advanceFn) {
        List<S> claimed = tx.execute(status ->
            sagaPort.claimDue(batchSize, activeStates(), workerId, leaseTtl)
        );
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        log.debug("[{}] claimed {} saga(s)", workerId, claimed.size());
        for (S saga : claimed) {
            try {
                tx.executeWithoutResult(status -> {
                    advanceFn.accept(saga);
                    saga.releaseLease();
                    sagaPort.save(saga);
                });
            } catch (RuntimeException ex) {
                log.warn("[{}] saga {} advance failed: {}", workerId, saga.sagaId(), ex.toString());
                try {
                    tx.executeWithoutResult(status -> {
                        // Reload to discard any in-memory mutations made before the
                        // advance threw; otherwise we'd persist a partial transition.
                        sagaPort.findBySagaId(saga.sagaId()).ifPresent(reloaded -> {
                            reloaded.scheduleRetry(
                                Instant.now().plus(retryBackoff),
                                ex.getClass().getSimpleName() + ": " + ex.getMessage()
                            );
                            reloaded.releaseLease();
                            sagaPort.save(reloaded);
                        });
                    });
                } catch (RuntimeException nested) {
                    log.error("[{}] could not record retry for saga {}: {}",
                        workerId, saga.sagaId(), nested.toString());
                }
            }
        }
    }
}
