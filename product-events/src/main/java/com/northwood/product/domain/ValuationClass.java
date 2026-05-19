package com.northwood.product.domain;

/**
 * Mirrors the schema CHECK on {@code product.product.valuation_class}
 * (and {@code finance.product_card.valuation_class}). The string values
 * must match the database literals exactly.
 *
 * <p>Hosted in {@code product-events} (not {@code product-service}) because
 * it is the cross-service contract carried on the wire format of
 * {@link com.northwood.product.domain.events.ValuationClassChanged} — and
 * because finance's GL account-selection policy
 * ({@code JournalEntryService.inventoryAccountForProduct} +
 * {@code cogsAccountForProduct}) imports the same enum from here, switching
 * over the typed values rather than string literals. Same pattern as
 * {@link ProductType}.
 *
 * <p>Note the {@code _goods} plural suffix — distinct from
 * {@link ProductType}'s singular {@code _good} ({@code raw_material},
 * {@code finished_good}, {@code semi_finished_good}). The two are different
 * fields: {@code product_type} categorises the master record, while
 * {@code valuation_class} categorises the costing/accounting treatment.
 */
public enum ValuationClass {
    RAW_MATERIALS("raw_materials"),
    FINISHED_GOODS("finished_goods"),
    SEMI_FINISHED_GOODS("semi_finished_goods");

    private final String dbValue;

    ValuationClass(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ValuationClass fromDb(String value) {
        for (ValuationClass c : values()) {
            if (c.dbValue.equals(value)) return c;
        }
        throw new IllegalArgumentException("Unknown valuation_class: " + value);
    }
}
