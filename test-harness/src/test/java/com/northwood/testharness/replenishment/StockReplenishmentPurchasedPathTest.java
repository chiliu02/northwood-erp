package com.northwood.testharness.replenishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import com.northwood.purchasing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.PurchasingTestKit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice F — purchased-path replenishment, end-to-end.
 *
 * <p>Drives the full §2.35 lifecycle for a purchasable raw-material
 * replenishment:
 * <ol>
 *   <li>Inventory's reorder-point detection trigger raises a
 *       {@code ReplenishmentRequest(status=requested,
 *       targetService=purchasing, reason=reorder_point_breach)} and emits
 *       {@code inventory.ReplenishmentRequested}.</li>
 *   <li>Purchasing's {@code ReplenishmentRequestedHandler} creates a PR via
 *       {@code PurchaseRequisitionService.createForStockReplenishment(...)},
 *       which emits {@code PurchaseRequisitionCreated(sourceType='stock_replenishment')}
 *       AND {@code purchasing.ReplenishmentDispatched}. Auto-conversion then
 *       creates a PO and emits {@code PurchaseOrderCreated}.</li>
 *   <li>Inventory's {@code PurchasingReplenishmentDispatchedHandler}
 *       consumes the dispatch event and flips the request to
 *       {@code dispatched} (kind=purchase_requisition, id=prId).</li>
 *   <li>Inventory's {@code PurchaseOrderCreatedHandler} (Slice E extension)
 *       finds the open replenishment by
 *       {@code dispatched_aggregate_id = sourcePurchaseRequisitionId} and
 *       stamps {@code linked_purchase_order_id = poId}.</li>
 *   <li>The harness shortcuts the goods-received step by invoking the
 *       fulfilment path directly (the inline {@code GoodsReceiptService.post}
 *       integration is exercised separately by
 *       {@code GoodsReceiptServiceTest}, which mocks the repository). We
 *       call {@code findByLinkedPurchaseOrderId → markFulfilled → save},
 *       which is exactly what the production path does inside the receipt
 *       service.</li>
 * </ol>
 */
class StockReplenishmentPurchasedPathTest {

    @Test
    void reorder_point_breach_creates_pr_then_po_then_fulfils_on_receipt() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        PurchasingTestKit pur = new PurchasingTestKit(bus, json);
        InventoryTestKit inv = new InventoryTestKit(bus, json);

        // Seed: a purchasable raw material.
        UUID rawProductId = UUID.randomUUID();
        UUID defaultSupplierId = pur.suppliers.findByCode("SUP-001").orElseThrow().id().value();
        pur.priceLookup.put(defaultSupplierId, rawProductId, Currencies.AUD, new BigDecimal("12.50"));

        // Make-vs-buy: purchased.
        inv.productReplenishment.put(rawProductId, /*purchased=*/true, /*manufactured=*/false);

        // Reorder policy: reorder_point=5, reorder_quantity=10. Pre-decrement
        // balance set below the threshold.
        inv.reorderPolicies.put(rawProductId, new BigDecimal("5"), new BigDecimal("10"));
        inv.stockBalances.seedOnHand(InventoryTestKit.DEFAULT_WAREHOUSE_ID, rawProductId, new BigDecimal("3"));

        // ───────────────────────────────────────────────────────────────────
        // Step 1: trigger the detection.
        inv.replenishmentDetection.checkAfterOnHandDecrement(
            InventoryTestKit.DEFAULT_WAREHOUSE_ID, rawProductId
        );

        List<ReplenishmentRequest> requests = inv.replenishmentRequests.all();
        assertThat(requests).hasSize(1);
        ReplenishmentRequest r = requests.get(0);
        assertThat(r.targetService()).isEqualTo(ReplenishmentRequest.TargetService.PURCHASING);
        assertThat(r.status()).isEqualTo(ReplenishmentRequest.Status.REQUESTED);

        // Drain: pur consumes ReplenishmentRequested → creates PR + PO →
        // emits PurchaseRequisitionCreated + purchasing.ReplenishmentDispatched
        // + PurchaseOrderCreated. inv consumes ReplenishmentDispatched +
        // PurchaseOrderCreated → flips to dispatched + stamps linked PO.
        bus.drain();

        // Assert: a stock-replenishment PR was created.
        PurchaseRequisitionCreated prCreated = (PurchaseRequisitionCreated) pur.outbox.all().stream()
            .filter(o -> PurchaseRequisitionCreated.EVENT_TYPE.equals(o.getEventType()))
            .findFirst()
            .map(o -> {
                try { return json.readValue(o.getPayload(), PurchaseRequisitionCreated.class); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow();
        assertThat(prCreated.sourceType()).isEqualTo(PurchaseRequisition.SourceType.STOCK_REPLENISHMENT.dbValue());
        assertThat(prCreated.sourceReplenishmentRequestId()).isEqualTo(r.id().value());
        UUID prId = prCreated.aggregateId();

        // Assert: purchasing.ReplenishmentDispatched was emitted.
        assertThat(pur.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(ReplenishmentDispatched.EVENT_TYPE);

        // Assert: a PO was created (auto-conversion).
        PurchaseOrderCreated poCreated = (PurchaseOrderCreated) pur.outbox.all().stream()
            .filter(o -> PurchaseOrderCreated.EVENT_TYPE.equals(o.getEventType()))
            .findFirst()
            .map(o -> {
                try { return json.readValue(o.getPayload(), PurchaseOrderCreated.class); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow();
        UUID poId = poCreated.aggregateId();
        assertThat(poCreated.purchaseRequisitionHeaderId()).isEqualTo(prId);

        // Assert: inv flipped to dispatched + stamped the PR id.
        ReplenishmentRequest afterDispatch = inv.replenishmentRequests
            .findById(r.id())
            .orElseThrow();
        assertThat(afterDispatch.status()).isEqualTo(ReplenishmentRequest.Status.DISPATCHED);
        assertThat(afterDispatch.dispatchedAggregateKind())
            .isEqualTo(ReplenishmentRequest.DispatchedAggregateKind.PURCHASE_REQUISITION);
        assertThat(afterDispatch.dispatchedAggregateId()).isEqualTo(prId);

        // Assert: inv linked the PO via PurchaseOrderCreated.
        assertThat(afterDispatch.linkedPurchaseOrderId()).isEqualTo(poId);

        // ───────────────────────────────────────────────────────────────────
        // Step 2: simulate the goods receipt fulfilling the replenishment.
        // The production path is GoodsReceiptService.post → look up by
        // linkedPurchaseOrderId → markFulfilled + save. Exercised separately
        // by GoodsReceiptServiceTest with mocks; the harness shortcut runs
        // the same lookup + mutator + save sequence directly.
        inv.replenishmentRequests.findByLinkedPurchaseOrderId(poId)
            .filter(req -> req.status() == ReplenishmentRequest.Status.DISPATCHED)
            .ifPresent(req -> {
                req.markFulfilled();
                inv.replenishmentRequests.save(req);
            });

        // Assert: replenishment is fulfilled + ReplenishmentFulfilled emitted.
        ReplenishmentRequest afterFulfil = inv.replenishmentRequests
            .findById(r.id())
            .orElseThrow();
        assertThat(afterFulfil.status()).isEqualTo(ReplenishmentRequest.Status.FULFILLED);
        assertThat(afterFulfil.fulfilledAt()).isNotNull();
        assertThat(inv.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(
                ReplenishmentRequested.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE
            );
    }
}
