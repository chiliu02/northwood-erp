package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.Routing;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side query port for routings. The slice only needs an active-routing
 * lookup at work-order release; create/update flows are deferred until
 * manufacturing has a dedicated routing-edit user story.
 *
 * <p>Lives in {@code application/} per the {@code *QueryPort} convention —
 * {@link Routing} has no mutators, no {@code pendingEvents}, and no events
 * today, so it is not a DDD aggregate root and the surrounding port is not a
 * DDD {@code *Repository}. Promote back to {@code *Repository} if and when
 * mutators arrive.
 */
public interface RoutingQueryPort {

    Optional<Routing> findActiveByFinishedProductId(UUID finishedProductId);
}
