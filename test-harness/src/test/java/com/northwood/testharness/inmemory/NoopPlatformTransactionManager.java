package com.northwood.testharness.inmemory;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Test-only {@link PlatformTransactionManager} that returns a fresh
 * non-transactional status on every {@code getTransaction} and no-ops on
 * {@code commit}/{@code rollback}. Lets {@code TransactionTemplate} drive
 * harness saga workers without a real {@code DataSourceTransactionManager}.
 *
 * <p>Rollback semantics are absent here — production rolls saga state +
 * outbox writes back together when an advance throws; the harness re-raises
 * the exception so tests still see failures, just without DB-level rollback.
 * Acceptable because the harness is single-threaded and per-test fresh.
 */
public final class NoopPlatformTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        // no-op
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        // no-op
    }
}
