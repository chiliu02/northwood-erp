package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.domain.SupplierProductPrice;
import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.DomainEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InMemorySupplierProductPriceRepository implements SupplierProductPriceRepository {

    private record Key(UUID supplierId, UUID productId, String currency) {}

    private final Map<Key, SupplierProductPrice> byKey = new HashMap<>();
    private final Map<UUID, SupplierProductPrice> byId = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemorySupplierProductPriceRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<SupplierProductPrice> findByKey(UUID supplierId, UUID productId, String currencyCode) {
        return Optional.ofNullable(byKey.get(new Key(supplierId, productId, currencyCode)));
    }

    @Override
    public void save(SupplierProductPrice price) {
        Key key = new Key(price.supplierId(), price.productId(), price.currencyCode());
        byKey.put(key, price);
        byId.put(price.id().value(), price);
        for (DomainEvent event : price.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    SupplierProductPrice.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event),
                    null, null, null, null
                ));
            } catch (JacksonException e) {
                throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
            }
        }
    }

    @Override
    public List<SupplierProductPrice> listForSupplier(UUID supplierId) {
        List<SupplierProductPrice> out = new ArrayList<>();
        for (SupplierProductPrice p : byId.values()) {
            if (supplierId.equals(p.supplierId())) {
                out.add(p);
            }
        }
        return out;
    }
}
