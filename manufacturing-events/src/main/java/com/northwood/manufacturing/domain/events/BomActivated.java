package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by the {@code Bom} aggregate when a draft BOM transitions to
 * {@code active}. Manufacturing's view of "this specific BOM is now active";
 * distinct from {@code product.ActiveBomChanged} (which carries product
 * master's view of "the active BOM for finished product X is now Y" and is
 * emitted by {@code Product.activateBom} in product-service).
 *
 * <p>{@code aggregateId} is the {@code bom_header_id}. No consumer subscribes
 * today — the event exists so the BOM aggregate participates in the standard
 * {@code pendingEvents → outbox → Kafka} flow and future consumers
 * (planning audit, BOM-change notifications, etc.) can attach without
 * changing the aggregate.
 *
 * <p>Introduced 2026-05-16 (§2.16). Wire-format name {@code manufacturing.BomActivated}
 * does not collide with the retired {@code product.BomActivated} (renamed to
 * {@code product.ActiveBomChanged} in §2.13) — different service prefix,
 * different semantics.
 */
public record BomActivated(
    UUID eventId,
    UUID aggregateId,
    UUID finishedProductId,
    String finishedProductSku,
    String version,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.BomActivated";

    @Override public String eventType() { return EVENT_TYPE; }
}
