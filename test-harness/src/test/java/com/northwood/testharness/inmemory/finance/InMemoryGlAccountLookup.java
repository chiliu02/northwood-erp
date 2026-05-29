package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.application.GlAccountLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test-side {@link GlAccountLookup} implementation backed by a seeded map.
 * Each kit pre-seeds the standard chart of accounts the production code
 * expects: 1000/1100/1200/1210/1220/1300/2100/2110/4000/5000/5200.
 */
public final class InMemoryGlAccountLookup implements GlAccountLookup {

    private final Map<String, GlAccount> byCode = new HashMap<>();

    public InMemoryGlAccountLookup() {
        seedDefaults();
    }

    private void seedDefaults() {
        put("1000", "Bank");
        put("1100", "Accounts Receivable");
        put("1200", "Inventory");
        put("1210", "Raw Materials Inventory");
        put("1220", "Finished Goods Inventory");
        put("1300", "Goods Received Not Invoiced");
        put("2100", "Accounts Payable");
        put("2110", "Customer Deposits");
        put("4000", "Sales Revenue");
        put("5000", "Cost of Goods Sold");
        put("5200", "Materials COGS");
    }

    public InMemoryGlAccountLookup put(String code, String name) {
        byCode.put(code, new GlAccount(UUID.randomUUID(), code, name));
        return this;
    }

    @Override
    public GlAccount byCode(String accountCode) {
        GlAccount a = byCode.get(accountCode);
        if (a == null) {
            throw new IllegalStateException("GL account " + accountCode + " not seeded in InMemoryGlAccountLookup");
        }
        return a;
    }
}
