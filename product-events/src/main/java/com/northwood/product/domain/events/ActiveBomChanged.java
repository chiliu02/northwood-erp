package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Active-BoM pointer change for a manufactured SKU, owned by product master.
 * The BoM lines themselves stay in {@code manufacturing.bom_header} /
 * {@code bom_line}; only the "which BoM is the active one for this SKU
 * right now" decision is authoritative on product master.
 *
 * <p>{@code newBomHeaderId} is nullable to express all three transitions in
 * one event:
 *
 * <ul>
 *   <li><b>Activate</b> — {@code oldBomHeaderId=null},
 *       {@code newBomHeaderId=<id>}. First BoM made active for the product
 *       (the SKU's {@code product.active_bom_id} starts null on registration
 *       and is set by the first {@code Product.activateBom(...)} call).</li>
 *   <li><b>Switch / supersede</b> — {@code oldBomHeaderId=<a>},
 *       {@code newBomHeaderId=<b>}. Replace the existing active BoM with a
 *       new revision. The previous BoM is implicitly deactivated via the
 *       {@code manufacturing.product_card.active_bom_header_id} update (the row is keyed
 *       on {@code product_id}, so overwriting {@code active_bom_header_id}
 *       releases the old pointer atomically).</li>
 *   <li><b>Deactivate / retire</b> — {@code oldBomHeaderId=<a>},
 *       {@code newBomHeaderId=null}. The SKU is no longer buildable. Driven
 *       by {@code Product.activateBom(null)} — explicitly permitted per its
 *       Javadoc. {@code JdbcProductActiveBomProjection.apply} stores the
 *       null through; {@code findActiveBomId} then returns
 *       {@link java.util.Optional#empty()}. Downstream consumers (e.g. the
 *       §2.8 Slice C / D materials-cost rollup) treat the empty active BoM
 *       as "manufactured-without-BoM" and skip cost rollup with
 *       {@code reason="inputs_missing"}.</li>
 * </ul>
 *
 * <p>{@code Product.activateBom(...)} rejects any change against a
 * discontinued product, so this event never fires after
 * {@link ProductDiscontinued} for a given aggregate.
 *
 * <p>Manufacturing keeps its own {@code bom_header.is_active} column during
 * the migration period — both can co-exist until the manufacturing side
 * switches fully to projecting product's authoritative pointer.
 *
 * <p><b>Renamed from {@code BomActivated} 2026-05-14 (§2.13).</b> The old
 * name implied activation only and read as a misnomer for the
 * {@code newBomHeaderId=null} retirement path. Wire format updated
 * alongside the Java class — no external subscribers existed at rename
 * time, so the wire break was deliberate (not a Java-only rename like the
 * {@code SalesOrderCancellationApplied} case where two classes with the
 * same simple name forced FQN imports).
 */
public record ActiveBomChanged(
    UUID eventId,
    UUID aggregateId,
    UUID oldBomHeaderId,
    UUID newBomHeaderId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "product.ActiveBomChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
