package com.northwood.manufacturing.domain;

import java.util.List;
import java.util.UUID;

/**
 * Routing template loaded read-side at work-order release. Manufacturing
 * doesn't write through this class today (routings are seeded into the schema
 * and edited via DB for the slice); the aggregate exists so future commands
 * can hang their domain events on it cleanly.
 */
public final class Routing {

    /** Status — wire-format strings stored in manufacturing.routing_header.status. */
    public static final String ACTIVE = "active";

    private final RoutingId id;
    private final UUID finishedProductId;
    private final String version;
    private final String status;
    private final List<RoutingOperation> operations;

    public Routing(
        RoutingId id,
        UUID finishedProductId,
        String version,
        String status,
        List<RoutingOperation> operations
    ) {
        this.id = id;
        this.finishedProductId = finishedProductId;
        this.version = version;
        this.status = status;
        this.operations = operations;
    }

    public RoutingId id()                       { return id; }
    public UUID finishedProductId()             { return finishedProductId; }
    public String version()                     { return version; }
    public String status()                      { return status; }
    public List<RoutingOperation> operations()  { return List.copyOf(operations); }
}
