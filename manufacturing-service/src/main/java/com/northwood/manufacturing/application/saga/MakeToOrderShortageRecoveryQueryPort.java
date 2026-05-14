package com.northwood.manufacturing.application.saga;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * CQRS read-side port for the make-to-order saga's shortage-recovery flow.
 * Given a set of received product ids (from {@code inventory.GoodsReceived}),
 * returns the saga ids of any in-progress make-to-order sagas in
 * {@code raw_material_shortage} whose work order needs at least one of the
 * received products.
 *
 * <p>Why a separate port (not on {@code MakeToOrderSagaPort}): the saga port
 * stays focused on saga lifecycle (claim/save/findByX). This query joins
 * {@code make_to_order_saga} to {@code work_order_material} to filter by
 * the received-product set — closer to a CQRS read model than to saga
 * lifecycle. Keeping it separate makes the saga port's surface area small
 * and signals "this is a query, not a state mutation".
 */
public interface MakeToOrderShortageRecoveryQueryPort {

    /**
     * Find saga ids in {@code raw_material_shortage} whose work order has at
     * least one material whose component product id is in {@code productIds}.
     * Empty list when no candidates match.
     */
    List<UUID> findShortageSagaIdsForReceivedProducts(Collection<UUID> productIds);
}
