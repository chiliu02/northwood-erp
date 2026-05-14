package com.northwood.purchasing.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.purchasing.application.dto.PriceView;
import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.purchasing.domain.SupplierProductPriceRepository.ExistingPrice;
import com.northwood.purchasing.domain.events.SupplierProductPriceChanged;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for authoring the supplier price list. Handles the
 * upsert against {@code purchasing.supplier_product_price} (via
 * {@link SupplierProductPriceRepository}) and writes a
 * {@code purchasing.SupplierProductPriceChanged} event to the outbox in
 * the same transaction so downstream consumers (a future reporting view
 * tracking price history; weighted-average cost recompute when paired
 * with perpetual inventory) can react.
 */
@Service
public class SupplierProductPriceService {

    private static final Logger log = LoggerFactory.getLogger(SupplierProductPriceService.class);
    private static final String DEFAULT_CURRENCY = "AUD";

    private final SupplierProductPriceRepository supplierProductPrices;
    private final OutboxPort outbox;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public SupplierProductPriceService(
        SupplierProductPriceRepository supplierProductPrices,
        OutboxPort outbox,
        ObjectMapper json,
        CurrentUserAccessor currentUser
    ) {
        this.supplierProductPrices = supplierProductPrices;
        this.outbox = outbox;
        this.json = json;
        this.currentUser = currentUser;
    }

    /**
     * Set or update the unit price for a (supplier, product, currency)
     * tuple. Returns the row's PK (existing or newly minted).
     *
     * <p>§3.10: no-op suppression — if the new {@code unitPrice} compares
     * equal to the existing one (via {@link BigDecimal#compareTo}, so
     * {@code 100.00 == 100.0}) the call is a no-op: no UPDATE, no
     * version bump, no event emitted. Suppression is logged at INFO so
     * it's visible during development. Suppression only applies on the
     * UPDATE path; first-time inserts always emit.
     */
    @Transactional
    public UUID setPrice(UUID supplierId, UUID productId, String currencyCode, BigDecimal unitPrice) {
        if (supplierId == null) throw new IllegalArgumentException("supplierId required");
        if (productId == null) throw new IllegalArgumentException("productId required");
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be > 0");
        }
        String currency = currencyCode == null ? DEFAULT_CURRENCY : currencyCode;

        Optional<ExistingPrice> existing = supplierProductPrices.find(supplierId, productId, currency);
        UUID priceId = existing.map(ExistingPrice::priceId).orElseGet(UUID::randomUUID);
        BigDecimal oldPrice = existing.map(ExistingPrice::unitPrice).orElse(null);

        if (existing.isEmpty()) {
            supplierProductPrices.insert(priceId, supplierId, productId, currency, unitPrice);
        } else if (oldPrice != null && oldPrice.compareTo(unitPrice) == 0) {
            log.info("no-op setPrice: supplier={} product={} currency={} unchanged at {}",
                supplierId, productId, currency, unitPrice);
            return priceId;
        } else {
            supplierProductPrices.updatePrice(priceId, unitPrice);
        }

        appendOutbox(new SupplierProductPriceChanged(
            UUID.randomUUID(),
            priceId,
            supplierId,
            productId,
            currency,
            oldPrice,
            unitPrice,
            Instant.now()
        ));

        log.info("set supplier price: supplier={} product={} currency={} {} → {}",
            supplierId, productId, currency, oldPrice, unitPrice);
        return priceId;
    }

    /** Read-only listing for a supplier — used by future authoring UIs. */
    public List<PriceView> listForSupplier(UUID supplierId) {
        return supplierProductPrices.listForSupplier(supplierId).stream()
            .map(r -> new PriceView(
                r.supplierProductPriceId(),
                r.supplierId(),
                r.productId(),
                r.currencyCode(),
                r.unitPrice(),
                r.version()
            ))
            .toList();
    }

    private void appendOutbox(SupplierProductPriceChanged event) {
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                "SupplierProductPrice",
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                currentUser.currentUsername().orElse(null)
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }
}
