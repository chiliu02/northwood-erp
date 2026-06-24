package com.northwood.search.application;

import java.util.UUID;

/**
 * Write side of the {@code entities} cross-service read model (read model B).
 * The inbox handlers in {@code application/inbox/entity/} call this as each
 * entity-bearing event (customer/order/supplier/PO/invoice/product) arrives.
 *
 * <p>Idempotent and order-tolerant: the document id is {@code <entityType>:<id>},
 * so a re-delivery upserts the same document. The Elasticsearch implementation
 * lives in {@code infrastructure/elasticsearch/}.
 */
public interface GlobalEntityProjection {

    String TYPE_PRODUCT = "product";
    String TYPE_CUSTOMER = "customer";
    String TYPE_SALES_ORDER = "sales_order";
    String TYPE_SUPPLIER = "supplier";
    String TYPE_PURCHASE_ORDER = "purchase_order";
    String TYPE_CUSTOMER_INVOICE = "customer_invoice";

    /**
     * Upsert one searchable entity.
     *
     * @param entityType the discriminator ({@code EntityDocument.TYPE_*})
     * @param id         the entity's aggregate id
     * @param title      primary display text (product/customer/supplier name, or the document number)
     * @param code       the exact-match handle (sku / customer code / order or PO / invoice number)
     * @param subtitle   secondary text (email, counterparty name, product type)
     * @param status     lifecycle status (facet)
     */
    void upsertEntity(String entityType, UUID id, String title, String code, String subtitle, String status);
}
