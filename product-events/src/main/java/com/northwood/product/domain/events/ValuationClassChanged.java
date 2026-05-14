package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Valuation class — the costing/accounting category for a SKU. Drives
 * finance's GL account selection at posting time (e.g. {@code 'raw_materials'}
 * → 1200 Inventory + 1300 GRNI; {@code 'finished_goods'} → 1200 + 5000 COGS;
 * {@code 'sub_assemblies'} → WIP). Per the Shape A pattern, the
 * authoritative classification lives on product master; finance keeps a
 * read-side projection and reads from it when posting.
 *
 * <p>Carries old + new so consumers re-projecting historical decisions
 * don't have to re-query the master.
 */
public record ValuationClassChanged(
    UUID eventId,
    UUID aggregateId,
    String oldValuationClass,
    String newValuationClass,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "product.ValuationClassChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
