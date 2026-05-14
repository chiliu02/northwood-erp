package com.northwood.finance.application;

import java.util.UUID;

/**
 * Lightweight read port for {@code finance.gl_account}. Phase 5b's journal
 * postings reference 5 well-known accounts by code (1000 Bank, 1100 AR,
 * 2100 AP, 4000 Sales Revenue, 5000 COGS); this lookup resolves the account
 * id + name once per journal-line construction.
 */
public interface GlAccountLookup {

    GlAccount byCode(String accountCode);

    record GlAccount(UUID id, String code, String name) {}
}
