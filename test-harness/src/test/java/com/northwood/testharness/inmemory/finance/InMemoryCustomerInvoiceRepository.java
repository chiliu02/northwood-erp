package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.finance.domain.CustomerInvoiceRepository;
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

public final class InMemoryCustomerInvoiceRepository implements CustomerInvoiceRepository {

    private final Map<UUID, CustomerInvoice> store = new HashMap<>();
    private final Map<UUID, BigDecimal> paidByInvoice = new HashMap<>();
    private final java.util.Set<UUID> revenueRecognized = new java.util.HashSet<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryCustomerInvoiceRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<CustomerInvoice> findById(CustomerInvoiceId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public List<CustomerInvoice> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void save(CustomerInvoice invoice) {
        store.put(invoice.id().value(), invoice);
        for (DomainEvent event : invoice.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    CustomerInvoice.AGGREGATE_TYPE,
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
    public Optional<PaymentSnapshot> findPaymentSnapshot(UUID customerInvoiceHeaderId) {
        CustomerInvoice inv = store.get(customerInvoiceHeaderId);
        if (inv == null) return Optional.empty();
        BigDecimal paid = paidByInvoice.getOrDefault(customerInvoiceHeaderId, BigDecimal.ZERO);
        CustomerInvoice.Status status = paid.signum() <= 0 ? CustomerInvoice.Status.POSTED
            : (paid.compareTo(inv.totalAmount()) >= 0 ? CustomerInvoice.Status.PAID : CustomerInvoice.Status.PARTIALLY_PAID);
        return Optional.of(new PaymentSnapshot(
            inv.customerId(), inv.customerName(), inv.salesOrderHeaderId(),
            inv.currencyCode(), inv.totalAmount(), paid, status,
            inv.invoiceType()
        ));
    }

    @Override
    public Optional<ShipmentTimeInvoice> findInvoiceForShipment(UUID salesOrderHeaderId) {
        return store.values().stream()
            .filter(inv -> salesOrderHeaderId.equals(inv.salesOrderHeaderId()))
            .findFirst()
            .map(inv -> new ShipmentTimeInvoice(
                inv.id().value(),
                inv.invoiceNumber(),
                inv.invoiceType(),
                inv.customerName(),
                inv.currencyCode(),
                inv.totalAmount(),
                revenueRecognized.contains(inv.id().value())
            ));
    }

    @Override
    public boolean markRevenueRecognized(UUID customerInvoiceHeaderId) {
        return revenueRecognized.add(customerInvoiceHeaderId);
    }

    /**
     * Test-side: stand-in for the {@code maintain_allocation_totals} DB
     * trigger. Production updates {@code paid_amount} + flips status when a
     * payment allocates; the harness updates this map.
     */
    public void recordAllocation(UUID customerInvoiceHeaderId, BigDecimal amount) {
        paidByInvoice.merge(customerInvoiceHeaderId, amount, BigDecimal::add);
    }
}
