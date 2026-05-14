package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySupplierProductPriceRepository implements SupplierProductPriceRepository {

    private record Key(UUID supplierId, UUID productId, String currency) {}

    private static final class Row {
        UUID priceId;
        UUID supplierId;
        UUID productId;
        String currency;
        BigDecimal unitPrice;
        long version;
    }

    private final Map<Key, Row> byKey = new HashMap<>();
    private final Map<UUID, Row> byPriceId = new HashMap<>();

    @Override
    public Optional<ExistingPrice> find(UUID supplierId, UUID productId, String currencyCode) {
        Row r = byKey.get(new Key(supplierId, productId, currencyCode));
        return Optional.ofNullable(r == null ? null : new ExistingPrice(r.priceId, r.unitPrice));
    }

    @Override
    public void insert(UUID priceId, UUID supplierId, UUID productId, String currencyCode, BigDecimal unitPrice) {
        Row r = new Row();
        r.priceId = priceId;
        r.supplierId = supplierId;
        r.productId = productId;
        r.currency = currencyCode;
        r.unitPrice = unitPrice;
        r.version = 0;
        byKey.put(new Key(supplierId, productId, currencyCode), r);
        byPriceId.put(priceId, r);
    }

    @Override
    public void updatePrice(UUID priceId, BigDecimal newUnitPrice) {
        Row r = byPriceId.get(priceId);
        if (r == null) throw new IllegalStateException("price " + priceId + " not found");
        r.unitPrice = newUnitPrice;
        r.version += 1;
    }

    @Override
    public List<PriceRow> listForSupplier(UUID supplierId) {
        List<PriceRow> out = new ArrayList<>();
        for (Row r : byPriceId.values()) {
            if (supplierId.equals(r.supplierId)) {
                out.add(new PriceRow(r.priceId, r.supplierId, r.productId, r.currency, r.unitPrice, r.version));
            }
        }
        return out;
    }
}
