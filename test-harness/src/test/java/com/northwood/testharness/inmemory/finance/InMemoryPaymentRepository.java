package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.PaymentId;
import com.northwood.finance.domain.PaymentRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<UUID, Payment> store = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryPaymentRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public List<Payment> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void save(Payment payment) {
        store.put(payment.id().value(), payment);
        for (DomainEvent event : payment.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    "Payment",
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
}
