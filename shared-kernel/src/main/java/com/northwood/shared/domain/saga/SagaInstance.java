package com.northwood.shared.domain.saga;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Common shape for a saga state row across services. Subclasses add the
 * domain key (e.g. {@code sales_order_id}, {@code work_order_id}) and the
 * static factory that initialises a fresh row.
 *
 * <p>Lives in {@code shared-kernel} (framework-free, same module as
 * {@code Money}, {@code Sku}, {@code DomainEvent}) so service domain
 * aggregates can extend it without crossing into the application layer.
 * The matching {@code SagaPort} / {@code SagaManager} live in
 * {@code shared.application.saga} and operate over this kernel type.
 *
 * <p>The fields here mirror the lease/retry/version columns on every
 * {@code *_saga} table in {@code db/northwood_erp.sql}: {@code saga_id},
 * {@code saga_state}, {@code current_step}, {@code last_error},
 * {@code retry_count}, {@code next_retry_at}, {@code lease_owner},
 * {@code lease_expires_at}, {@code version}, {@code data}, {@code created_at},
 * {@code updated_at}, {@code completed_at}.
 *
 * <p>Mutators here are intent-named so saga manager subclasses can advance
 * the saga through its state machine without poking at fields.
 */
public abstract class SagaInstance {

    private final UUID sagaId;
    private String state;
    private String currentStep;
    private String lastError;
    private int retryCount;
    private Instant nextRetryAt;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private long version;
    private String dataJson;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    /**
     * Transient marker — set by {@link #transitionTo} and consumed by the
     * persistence adapter ({@code consumeStateAdvanced()}) to decide whether a
     * saga-milestone span should be recorded for this save. Defaults false on a
     * freshly reconstituted row, so a data-only update or a retry reschedule
     * (no {@code transitionTo}) records no milestone. Not a persisted column.
     */
    private boolean stateAdvanced;

    protected SagaInstance(
        UUID sagaId,
        String state,
        String currentStep,
        String lastError,
        int retryCount,
        Instant nextRetryAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        long version,
        String dataJson,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        this.sagaId = sagaId;
        this.state = state;
        this.currentStep = currentStep;
        this.lastError = lastError;
        this.retryCount = retryCount;
        this.nextRetryAt = nextRetryAt;
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.version = version;
        this.dataJson = dataJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
    }

    /** Saga states that mean the saga is finished and should not be polled. */
    public abstract Set<String> terminalStates();

    /** Move to a new state, clearing transient retry/error metadata. */
    public void transitionTo(String newState, String newStep) {
        this.state = newState;
        this.currentStep = newStep;
        this.lastError = null;
        this.retryCount = 0;
        this.nextRetryAt = Instant.now();
        this.stateAdvanced = true;
        if (terminalStates().contains(newState)) {
            this.completedAt = Instant.now();
        }
    }

    /**
     * Returns whether a {@link #transitionTo} has occurred since this row
     * was loaded/created, and resets the marker. Called by the persistence
     * adapter on {@code update()} so a saga-milestone span is recorded only when
     * the state actually advanced (not on data-only updates or retry reschedules).
     */
    public boolean consumeStateAdvanced() {
        boolean advanced = this.stateAdvanced;
        this.stateAdvanced = false;
        return advanced;
    }

    /** Defer the next attempt and record the error that triggered it. */
    public void scheduleRetry(Instant when, String error) {
        this.nextRetryAt = when;
        this.retryCount += 1;
        this.lastError = error;
    }

    /** Park: nothing to do until an external event arrives. Stretch nextRetryAt out. */
    public void parkUntil(Instant when) {
        this.nextRetryAt = when;
    }

    /** Acquire a lease — set when the saga port claims this row for processing. */
    public void acquireLease(String leaseOwner, Instant leaseExpiresAt) {
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    /** Release a lease — typically called after save() when work is done. */
    public void releaseLease() {
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    public void setDataJson(String dataJson) {
        this.dataJson = dataJson;
    }

    public void incrementVersion() {
        this.version += 1;
    }

    public UUID sagaId()             { return sagaId; }
    public String state()            { return state; }
    public String currentStep()      { return currentStep; }
    public String lastError()        { return lastError; }
    public int retryCount()          { return retryCount; }
    public Instant nextRetryAt()     { return nextRetryAt; }
    public String leaseOwner()       { return leaseOwner; }
    public Instant leaseExpiresAt()  { return leaseExpiresAt; }
    public long version()            { return version; }
    public String dataJson()         { return dataJson; }
    public Instant createdAt()       { return createdAt; }
    public Instant updatedAt()       { return updatedAt; }
    public Instant completedAt()     { return completedAt; }
}
