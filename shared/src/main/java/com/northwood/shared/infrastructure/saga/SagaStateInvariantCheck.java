package com.northwood.shared.infrastructure.saga;

import java.util.Set;

/**
 * Per-saga registration declaring which states the application code can write
 * to a saga state table. {@link SagaStateInvariantChecker} runs each
 * registration on startup against the actual schema CHECK constraint and
 * fails fast if any code state is missing from the DB list — the failure
 * mode that bit us on 2026-05-05 (`invoice_partially_paid` saga state written by code
 * was not in the baseline CHECK; mocked unit tests passed; a real partial
 * customer payment would have failed at INSERT).
 *
 * <p>Each service registers one of these per saga it owns, e.g. via a
 * {@code @Bean} method in a {@code @Configuration}.
 */
public interface SagaStateInvariantCheck {

    /** Schema the saga lives in (e.g. {@code "sales"}). */
    String schemaName();

    /** Saga table name (e.g. {@code "sales_order_fulfilment_saga"}). */
    String tableName();

    /** Column carrying the saga state literal — almost always {@code "saga_state"}. */
    default String columnName() { return "saga_state"; }

    /** Every state the application code can transition into. */
    Set<String> codeStates();
}
