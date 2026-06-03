package com.northwood.purchasing.application;

import com.northwood.purchasing.application.dto.SupplierView;
import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.SupplierRepository;
import com.northwood.shared.application.exception.ConflictException;
import com.northwood.shared.application.exception.NotFoundException;
import com.northwood.shared.domain.Assert;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the {@link Supplier} master aggregate: read the list /
 * detail, onboard a new supplier, change status (active/inactive/blocked), and
 * edit details. Mirrors the sales {@code CustomerService} shape — the aggregate
 * owns the state machine + event emission; the repository drains pending events
 * to the outbox on save.
 */
@Service
public class SupplierService {

    /** Onboarding a supplier_code that already exists (HTTP 409). */
    public static class DuplicateSupplierCodeException extends ConflictException {
        public static final String CODE = "DUPLICATE_SUPPLIER_CODE";
        private final String supplierCode;
        public DuplicateSupplierCodeException(String supplierCode) {
            super(CODE, "A supplier with code '" + supplierCode + "' already exists");
            this.supplierCode = supplierCode;
        }
        @Override public Map<String, Object> params() { return Map.of("supplierCode", supplierCode); }
    }

    /** Command targets a supplier id that doesn't exist (HTTP 404). */
    public static class SupplierNotFoundException extends NotFoundException {
        public static final String CODE = "SUPPLIER_NOT_FOUND";
        private final UUID supplierId;
        public SupplierNotFoundException(UUID supplierId) {
            super(CODE, "No supplier " + supplierId);
            this.supplierId = supplierId;
        }
        @Override public Map<String, Object> params() { return Map.of("supplierId", supplierId); }
    }

    private static final Logger log = LoggerFactory.getLogger(SupplierService.class);

    private final SupplierRepository suppliers;

    public SupplierService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    @Transactional(readOnly = true)
    public Optional<SupplierView> findById(UUID supplierId) {
        return suppliers.findById(SupplierId.of(supplierId)).map(SupplierView::from);
    }

    @Transactional(readOnly = true)
    public List<SupplierView> findAll() {
        return suppliers.findAll().stream().map(SupplierView::from).toList();
    }

    /** Onboard a new supplier (lands {@code active}). Rejects a duplicate code. */
    @Transactional
    public SupplierView onboard(String supplierCode, String name, String email, String phone, String address) {
        Assert.notBlank(supplierCode, "supplierCode required");
        if (suppliers.existsByCode(supplierCode)) {
            throw new DuplicateSupplierCodeException(supplierCode);
        }
        Supplier supplier = Supplier.register(supplierCode, name, email, phone, address);
        suppliers.save(supplier);
        log.info("onboarded supplier {} ({})", supplier.id().value(), supplierCode);
        return SupplierView.from(supplier);
    }

    /** Change a supplier's status (active/inactive/blocked). */
    @Transactional
    public SupplierView changeStatus(UUID supplierId, String newStatus, String reason) {
        Supplier supplier = suppliers.findById(SupplierId.of(supplierId))
            .orElseThrow(() -> new SupplierNotFoundException(supplierId));
        supplier.changeStatus(Supplier.Status.fromDb(newStatus), reason);
        suppliers.save(supplier);
        log.info("changed supplier {} status -> {} (reason={})", supplierId, newStatus, reason);
        return SupplierView.from(supplier);
    }

    /** Edit a supplier's details (name / email / phone / address). */
    @Transactional
    public SupplierView updateDetails(UUID supplierId, String name, String email, String phone, String address) {
        Supplier supplier = suppliers.findById(SupplierId.of(supplierId))
            .orElseThrow(() -> new SupplierNotFoundException(supplierId));
        supplier.updateDetails(name, email, phone, address);
        suppliers.save(supplier);
        log.info("updated supplier {} details", supplierId);
        return SupplierView.from(supplier);
    }
}
