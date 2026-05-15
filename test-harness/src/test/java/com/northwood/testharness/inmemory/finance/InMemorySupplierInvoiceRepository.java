package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceId;
import com.northwood.finance.domain.SupplierInvoiceRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InMemorySupplierInvoiceRepository implements SupplierInvoiceRepository {

    private final Map<UUID, SupplierInvoice> store = new HashMap<>();
    private final Map<UUID, BigDecimal> paidByInvoice = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemorySupplierInvoiceRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<SupplierInvoice> findById(SupplierInvoiceId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public void save(SupplierInvoice invoice) {
        store.put(invoice.id().value(), invoice);
        for (DomainEvent event : invoice.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    SupplierInvoice.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event),
                    null, null, null, null
                ));
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialise " + event.eventType(), e);
            }
        }
    }

    @Override
    public List<SupplierInvoice> findByStatus(String status) {
        List<SupplierInvoice> out = new ArrayList<>();
        for (SupplierInvoice inv : store.values()) {
            if (status.equals(inv.status())) out.add(inv);
        }
        return out;
    }

    @Override
    public List<SupplierInvoice> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public Optional<PaymentSnapshot> findPaymentSnapshot(UUID supplierInvoiceHeaderId) {
        SupplierInvoice inv = store.get(supplierInvoiceHeaderId);
        if (inv == null) return Optional.empty();
        BigDecimal paid = paidByInvoice.getOrDefault(supplierInvoiceHeaderId, BigDecimal.ZERO);
        String status = paid.signum() <= 0 ? inv.status()
            : (paid.compareTo(inv.totalAmount()) >= 0 ? SupplierInvoice.PAID : SupplierInvoice.PARTIALLY_PAID);
        return Optional.of(new PaymentSnapshot(
            inv.supplierId(), inv.supplierName(), inv.purchaseOrderHeaderId(),
            inv.currencyCode(), inv.totalAmount(), paid, status
        ));
    }

    /** Test-side: stand-in for the maintain_allocation_totals DB trigger. */
    public void recordAllocation(UUID supplierInvoiceHeaderId, BigDecimal amount) {
        paidByInvoice.merge(supplierInvoiceHeaderId, amount, BigDecimal::add);
    }
}
