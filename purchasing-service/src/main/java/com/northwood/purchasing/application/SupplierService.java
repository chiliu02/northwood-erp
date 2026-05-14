package com.northwood.purchasing.application;

import com.northwood.purchasing.application.dto.SupplierView;
import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.SupplierRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the {@link Supplier} aggregate. Read-only today —
 * supplier onboarding / edit commands land in a future slice when those
 * become user stories. The controller depends on this service rather than
 * reaching into {@link SupplierRepository} directly so the application layer
 * stays the single seam between API and domain.
 *
 * <p>Public methods return {@link SupplierView} rather than the
 * {@code Supplier} aggregate.
 */
@Service
public class SupplierService {

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
}
