package com.northwood.inventory.domain;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository {

    Optional<Shipment> findById(ShipmentId id);

    /**
     * All shipment headers (no lines), most recent first. List-view
     * endpoint only — detail fetch via {@link #findById} loads lines.
     */
    List<Shipment> findAllHeaders();

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(Shipment shipment);
}
