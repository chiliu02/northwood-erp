package com.northwood.manufacturing.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Read port for routings. The slice only needs an active-routing lookup at
 * work-order release; create/update flows are deferred until manufacturing has
 * a dedicated routing-edit user story.
 */
public interface RoutingRepository {

    Optional<Routing> findActiveByFinishedProductId(UUID finishedProductId);
}
