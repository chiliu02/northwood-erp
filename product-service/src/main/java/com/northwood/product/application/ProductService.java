package com.northwood.product.application;

import com.northwood.product.application.dto.ApprovedVendorCommand;
import com.northwood.product.application.dto.ProductView;
import com.northwood.product.domain.ApprovedVendor;
import com.northwood.product.domain.ApprovedVendorRepository;
import com.northwood.product.domain.Product;
import com.northwood.product.domain.ProductId;
import com.northwood.product.domain.ProductRepository;
import com.northwood.product.domain.ProductType;
import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the Product aggregate.
 *
 * <p>Each method is a use case: receive command-shaped input, load the
 * aggregate, invoke a method on it, persist (which also writes pending events
 * to the outbox in the same transaction). No business logic lives here — that
 * belongs on the aggregate.
 */
@Service
public class ProductService {

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(UUID id) {
            super("Product not found: " + id);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;
    private final ApprovedVendorRepository approvedVendors;

    public ProductService(ProductRepository products, ApprovedVendorRepository approvedVendors) {
        this.products = products;
        this.approvedVendors = approvedVendors;
    }

    @Transactional
    public ProductView createProduct(
        String sku,
        String name,
        String description,
        String productType,
        UUID baseUomId,
        BigDecimal salesPrice,
        BigDecimal standardCost,
        String currencyCode
    ) {
        Product product = Product.register(
            new Sku(sku),
            name,
            description,
            ProductType.fromDb(productType),
            baseUomId,
            Money.of(salesPrice, currencyCode),
            Money.of(standardCost, currencyCode)
        );
        products.save(product);
        return ProductView.from(product);
    }

    @Transactional(readOnly = true)
    public Optional<ProductView> findById(UUID productId) {
        return products.findById(ProductId.of(productId)).map(ProductView::from);
    }

    /** All products, ordered by SKU. Used by the demo UI catalog list. */
    @Transactional(readOnly = true)
    public List<ProductView> findAll() {
        return products.findAll().stream().map(ProductView::from).toList();
    }

    @Transactional
    public void changeSalesPrice(
        UUID productId,
        BigDecimal newSalesPrice,
        String currencyCode
    ) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        Money newPrice = Money.of(newSalesPrice, currencyCode);
        if (newPrice.equalsByValue(product.salesPrice())) {
            log.debug("changeSalesPrice product_id={} ignored — value unchanged ({})", productId, newPrice);
            return;
        }
        product.changeSalesPrice(newPrice);
        products.save(product);
    }

    @Transactional
    public void changeStandardCost(
        UUID productId,
        BigDecimal newStandardCost,
        String currencyCode
    ) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        Money newCost = Money.of(newStandardCost, currencyCode);
        if (newCost.equalsByValue(product.standardCost())) {
            log.debug("changeStandardCost product_id={} ignored — value unchanged ({})", productId, newCost);
            return;
        }
        product.changeStandardCost(newCost);
        products.save(product);
    }

    @Transactional
    public void setReorderPolicy(
        UUID productId,
        BigDecimal reorderPoint,
        BigDecimal reorderQuantity
    ) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (reorderPoint.compareTo(product.reorderPoint()) == 0
            && reorderQuantity.compareTo(product.reorderQuantity()) == 0) {
            log.debug("setReorderPolicy product_id={} ignored — values unchanged (point={}, qty={})",
                productId, reorderPoint, reorderQuantity);
            return;
        }
        product.changeReorderPolicy(reorderPoint, reorderQuantity);
        products.save(product);
    }

    @Transactional
    public void changeMakeVsBuy(
        UUID productId,
        boolean isPurchased,
        boolean isManufactured
    ) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (product.isPurchased() == isPurchased && product.isManufactured() == isManufactured) {
            log.debug("changeMakeVsBuy product_id={} ignored — flags unchanged (purchased={}, manufactured={})",
                productId, isPurchased, isManufactured);
            return;
        }
        product.changeMakeVsBuy(isPurchased, isManufactured);
        products.save(product);
    }

    @Transactional
    public void setValuationClass(UUID productId, String valuationClass) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (valuationClass != null && valuationClass.equals(product.valuationClass())) {
            log.debug("setValuationClass product_id={} ignored — value unchanged ({})", productId, valuationClass);
            return;
        }
        product.changeValuationClass(valuationClass);
        products.save(product);
    }

    @Transactional
    public void activateBom(UUID productId, UUID bomHeaderId) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (Objects.equals(bomHeaderId, product.activeBomId())) {
            log.debug("activateBom product_id={} ignored — active BOM unchanged ({})", productId, bomHeaderId);
            return;
        }
        product.activateBom(bomHeaderId);
        products.save(product);
    }

    /**
     * Replace the approved-vendor list for a product. The list is persisted
     * via {@link ApprovedVendorRepository} (separate table {@code product.approved_vendor})
     * and the aggregate emits {@link com.northwood.product.domain.events.ApprovedVendorListChanged} carrying the
     * full new list for downstream consumers.
     *
     * <p>The vendor list is not aggregate state (it lives in a sibling table),
     * so the no-op check happens here in the service rather than on the
     * aggregate. The aggregate retains its own discontinued check as
     * defense-in-depth.
     */
    @Transactional
    public void setApprovedVendors(
        UUID productId,
        List<ApprovedVendorCommand> vendors
    ) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (product.status() == Product.Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change approved vendors on a discontinued product");
        }
        Objects.requireNonNull(vendors, "vendors");
        List<ApprovedVendor> mapped = vendors.stream()
            .map(v -> new ApprovedVendor(v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()))
            .toList();
        List<ApprovedVendor> existing = approvedVendors.findForProduct(productId);
        if (sameVendorSet(existing, mapped)) {
            log.debug("setApprovedVendors product_id={} ignored — vendor set unchanged ({} entries)",
                productId, mapped.size());
            return;
        }
        approvedVendors.replaceFor(productId, mapped);
        product.emitApprovedVendorListChanged(mapped);
        products.save(product);
    }

    private static boolean sameVendorSet(
        List<ApprovedVendor> a,
        List<ApprovedVendor> b
    ) {
        if (a.size() != b.size()) return false;
        Set<ApprovedVendor> sa = new HashSet<>(a);
        Set<ApprovedVendor> sb = new HashSet<>(b);
        return sa.equals(sb);
    }

    @Transactional
    public void discontinue(UUID productId) {
        Product product = products.findById(ProductId.of(productId))
            .orElseThrow(() -> new ProductNotFoundException(productId));
        product.discontinue();
        products.save(product);
    }

}
