package com.northwood.inventory.application;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a {@link ReplenishmentRequest} inside its own savepoint so that a
 * {@code DuplicateKeyException} from the one-open-per-(product, warehouse)
 * partial unique index rolls back <em>only</em> the failed insert (and the
 * outbox rows the aggregate {@code save} drained), not the surrounding
 * transaction.
 *
 * <p>Why this is a separate bean rather than a method on
 * {@link ReplenishmentDetectionService}: {@code Propagation.NESTED} only takes
 * effect across a Spring proxy boundary. A self-invoked NESTED method silently
 * joins the outer transaction with no savepoint, so the duplicate-key insert
 * would leave the whole PostgreSQL transaction aborted (SQLSTATE 25P02 on the
 * next statement — e.g. the inbox-message record write). On the inbox-consumer
 * path that poisons the handler transaction and wedges the consumer in an
 * infinite, non-recovering retry. The savepoint contains the failed insert so
 * the caller can catch the duplicate and carry on against a clean connection.
 */
@Component
public class ReplenishmentRequestSaver {

    private final ReplenishmentRequestRepository replenishmentRequests;

    public ReplenishmentRequestSaver(ReplenishmentRequestRepository replenishmentRequests) {
        this.replenishmentRequests = replenishmentRequests;
    }

    /**
     * Persist the request (and drain its pending events to the outbox) within a
     * savepoint. Propagates {@code DuplicateKeyException} to the caller after
     * rolling back to the savepoint, so the one-open invariant is observed
     * without poisoning the outer transaction.
     */
    @Transactional(propagation = Propagation.NESTED)
    public void save(ReplenishmentRequest request) {
        replenishmentRequests.save(request);
    }
}
