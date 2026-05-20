package com.northwood.purchasing.application;

import com.northwood.purchasing.application.dto.PriceView;
import com.northwood.purchasing.domain.SupplierProductPrice;
import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for authoring the supplier price list. Thin orchestrator
 * around the {@link SupplierProductPrice} aggregate; the aggregate owns the
 * state machine (registration, price-change emission, no-op suppression) and
 * the repository drains {@code pendingEvents} to the outbox on save.
 *
 * <p>Promoted from a row-shaped service 2026-05-16 (§2.17). Previously this
 * class hand-rolled the outbox event; now the aggregate emits and the
 * repository persists in one transaction.
 */
@Service
public class SupplierProductPriceService {

    private static final Logger log = LoggerFactory.getLogger(SupplierProductPriceService.class);

    private final SupplierProductPriceRepository supplierProductPrices;

    public SupplierProductPriceService(SupplierProductPriceRepository supplierProductPrices) {
        this.supplierProductPrices = supplierProductPrices;
    }

    /**
     * Set or update the unit price for a (supplier, product, currency) tuple.
     * Returns the row's PK (existing or newly minted).
     *
     * <p>No-op suppression — if the new {@code unitPrice} compares equal to
     * the existing one (via {@link BigDecimal#compareTo}, so {@code 100.00 ==
     * 100.0}) the call is a no-op: no UPDATE, no version bump, no event
     * emitted. Suppression is logged at INFO so it's visible during
     * development. Suppression only applies on the UPDATE path; first-time
     * inserts always emit.
     */
    @Transactional
    public UUID setPrice(UUID supplierId, UUID productId, String currencyCode, BigDecimal unitPrice) {
        if (supplierId == null) throw new IllegalArgumentException("supplierId required");
        if (productId == null) throw new IllegalArgumentException("productId required");
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be > 0");
        }
        String currency = Currencies.orBase(currencyCode);

        Optional<SupplierProductPrice> existing = supplierProductPrices.findByKey(supplierId, productId, currency);
        SupplierProductPrice price;
        BigDecimal oldPrice;
        if (existing.isPresent()) {
            price = existing.get();
            oldPrice = price.unitPrice();
            if (oldPrice.compareTo(unitPrice) == 0) {
                log.info("no-op setPrice: supplier={} product={} currency={} unchanged at {}",
                    supplierId, productId, currency, unitPrice);
                return price.id().value();
            }
            price.updatePrice(unitPrice);
            supplierProductPrices.save(price);
        } else {
            price = SupplierProductPrice.register(supplierId, productId, currency, unitPrice);
            oldPrice = null;
            supplierProductPrices.save(price);
        }

        log.info("set supplier price: supplier={} product={} currency={} {} → {}",
            supplierId, productId, currency, oldPrice, unitPrice);
        return price.id().value();
    }

    /** Read-only listing for a supplier — used by future authoring UIs. */
    public List<PriceView> listForSupplier(UUID supplierId) {
        return supplierProductPrices.listForSupplier(supplierId).stream()
            .map(p -> new PriceView(
                p.id().value(),
                p.supplierId(),
                p.productId(),
                p.currencyCode(),
                p.unitPrice(),
                p.version()
            ))
            .toList();
    }
}
