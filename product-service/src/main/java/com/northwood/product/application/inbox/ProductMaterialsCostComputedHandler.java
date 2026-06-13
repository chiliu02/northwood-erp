package com.northwood.product.application.inbox;

import com.northwood.manufacturing.domain.events.ProductMaterialsCostComputed;
import com.northwood.product.application.ProductService;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Product-service's consumer of {@code manufacturing.ProductMaterialsCostComputed}.
 * Closes the cost-rollup feedback loop — when manufacturing rolls up a
 * product's cost (typically because a supplier price changed or a
 * BOM-component cost changed), this handler stamps the rolled-up
 * <b>standard cost</b> (material + own-routing conversion)
 * onto product master's {@code standard_cost} via the existing
 * {@link ProductService#changeStandardCost} mutator. That mutator emits
 * {@code product.StandardCostChanged}, which the already-wired finance
 * and reporting consumers pick up to refresh their projections.
 *
 * <p>This is the first inbox handler in product-service — product was previously
 * a pure Open Host (publisher only). The class is the
 * minimum-viable cost-loop closure; future product-service consumers
 * extend the same package.
 *
 * <p><b>Cascade safety.</b> A {@code StandardCostChanged} for a child
 * product will trigger manufacturing's rollup of every parent BOM, which
 * in turn emits a fresh {@code ProductMaterialsCostComputed} for each
 * parent — i.e. the cost-change propagates up the BOM. Termination
 * comes from two places: the no-op-on-unchanged-value guard inside
 * {@link ProductService#changeStandardCost} (idempotent on the same
 * (cost, currency) tuple) and manufacturing's own visited-set + walk-depth
 * cap inside {@code MaterialsCostRollupService}.
 *
 * <p><b>Null guards.</b> {@code materialsCost} and {@code currencyCode}
 * are nullable on the payload — manufacturing emits them as null when
 * the rollup determines the product has no computable materials cost
 * (e.g. inputs missing). When null, this handler logs and skips; the
 * inbox row is still marked processed so the event isn't redelivered
 * indefinitely.
 */
@Component
public class ProductMaterialsCostComputedHandler extends AbstractInboxHandler<ProductMaterialsCostComputed> {

    public static final String HANDLER_NAME = "product.materials-cost-rebased";

    private final ProductService productService;

    public ProductMaterialsCostComputedHandler(
        InboxPort inbox,
        ProductService productService,
        ObjectMapper json
    ) {
        super(inbox, json, ProductMaterialsCostComputed.class, ProductMaterialsCostComputed.EVENT_TYPE, HANDLER_NAME);
        this.productService = productService;
    }

    @Override
    protected void apply(ProductMaterialsCostComputed payload, EventEnvelope envelope) {
        // Prefer the full standard cost (material + conversion); fall back to
        // materialsCost for forward-compat with events emitted before the
        // standardCost field existed.
        var cost = payload.standardCost() != null ? payload.standardCost() : payload.materialsCost();
        if (cost == null || payload.currencyCode() == null) {
            log.info("[{}] skipping {} ({}) for product_id={} — cost/currencyCode null (reason={})",
                HANDLER_NAME, envelope.eventType(), envelope.eventId(),
                payload.aggregateId(), payload.reason());
            return;
        }

        productService.changeStandardCost(
            payload.aggregateId(),
            cost,
            payload.currencyCode()
        );

        log.info("[{}] applied {} ({}) for product_id={} → standard_cost={} {} (reason={})",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), cost, payload.currencyCode(),
            payload.reason());
    }
}
