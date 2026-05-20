package com.northwood.testharness.p2p;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.application.dto.RecordSupplierInvoiceCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentCommand;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.purchasing.application.dto.CreateRequisitionCommand;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderLine;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.FinanceTestKit;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.PurchasingTestKit;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.5.1 Slice F — purchase-to-pay happy path E2E test.
 *
 * <p>Manual PR → auto-converts to PO at {@code draft} → buyer approves →
 * saga → {@code purchase_order_approved} → worker → {@code waiting_for_goods}
 * → goods receipt (injected) → {@code goods_received} → supplier invoice
 * (3-way match) → {@code supplier_invoice_approved} → payment → {@code completed}.
 *
 * <p>Asserts saga state at each waypoint and the projection updates on
 * the receipt + payment paths.
 */
class PurchaseToPayHappyPathTest {

    @Test
    void manual_pr_to_completed_purchase_to_pay_saga() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        PurchasingTestKit purchasing = new PurchasingTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        // Seed: a product to purchase, supplier price.
        UUID productId = UUID.randomUUID();
        SupplierId defaultSupplierId = purchasing.suppliers.findByCode("SUP-001").orElseThrow().id();
        purchasing.priceLookup.put(defaultSupplierId.value(), productId, Currencies.AUD, new BigDecimal("25.00"));

        // Step 1: create manual PR. createManual auto-converts to PO at draft.
        var prView = purchasing.requisitionService.createManual(new CreateRequisitionCommand(
            "PR-1001",
            "alice@example.com",
            List.of(new RequisitionLineRequest(
                productId, "RM-101", "Raw Material 101",
                new BigDecimal("10"), null
            ))
        ));
        assertThat(prView).isNotNull();

        // Find the PO that was created.
        PurchaseOrder po = purchasing.orders.findById(findCreatedPoId(purchasing)).orElseThrow();
        assertThat(po.status()).isEqualTo(PurchaseOrder.Status.DRAFT);

        // Saga inserted at started.
        PurchaseToPaySaga sagaAtStart = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaAtStart.state()).isEqualTo(PurchaseToPaySaga.STARTED);

        // Step 2: buyer approves the PO. Saga → purchase_order_approved.
        purchasing.purchaseOrderService.approve(po.id().value(), "alice@example.com", "ok to send");
        PurchaseToPaySaga sagaAfterApprove = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaAfterApprove.state()).isEqualTo(PurchaseToPaySaga.PURCHASE_ORDER_APPROVED);

        // Step 3: drive worker. Saga → waiting_for_goods.
        purchasing.advanceSagaWorker();
        PurchaseToPaySaga sagaWaiting = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaWaiting.state()).isEqualTo(PurchaseToPaySaga.WAITING_FOR_GOODS);

        // Drain so finance.PurchaseOrderCreatedHandler seeds po_line_facts (from PurchaseOrderCreated).
        bus.drain();

        // Step 4: inject inventory.GoodsReceived. The receipt line maps to the
        // PO line by purchaseOrderLineId so finance's GoodsReceivedHandler bumps
        // received_quantity and the 3-way match later passes.
        PurchaseOrderLine poLine = po.lines().get(0);
        UUID receiptHeaderId = UUID.randomUUID();
        UUID receiptEventId = UUID.randomUUID();
        com.northwood.inventory.domain.events.GoodsReceived purchasingReceipt =
            new com.northwood.inventory.domain.events.GoodsReceived(
                receiptEventId, receiptHeaderId, "GR-001",
                po.id().value(), InventoryTestKit.DEFAULT_WAREHOUSE_ID, WarehouseCodes.MAIN,
                List.of(new com.northwood.inventory.domain.events.GoodsReceived.ReceivedLine(
                    UUID.randomUUID(), poLine.id(),
                    productId, "RM-101", "Raw Material 101",
                    new BigDecimal("10"), new BigDecimal("25.00")
                )),
                Instant.now()
            );
        // The same payload shape is used by both purchasing's and finance's
        // GoodsReceivedPayload. Inject onto inventory's outbox so the bus
        // delivers it to every registered handler subscribing to inventory.GoodsReceived.
        inventory.outbox.appendPending(OutboxRow.pending(
            receiptEventId, InventoryAggregateTypes.GOODS_RECEIPT, receiptHeaderId,
            GoodsReceived.EVENT_TYPE, 1,
            json.writeValueAsString(purchasingReceipt),
            null, null, null, null
        ));

        bus.drain();

        PurchaseToPaySaga sagaReceived = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaReceived.state()).isEqualTo(PurchaseToPaySaga.GOODS_RECEIVED);

        // Step 5: finance records the supplier invoice. 3-way match passes
        // because po_line_facts now has receivedQuantity=10 and unitPrice
        // matches.
        finance.supplierInvoiceService.recordInvoice(new RecordSupplierInvoiceCommand(
            "SI-INT-001",
            "INV-FROM-SUP-001",
            po.id().value(),
            receiptHeaderId,
            defaultSupplierId.value(),
            "SUP-001",
            "Default Supplier",
            Currencies.AUD,
            List.of(new RecordSupplierInvoiceCommand.Line(
                poLine.id(), UUID.randomUUID(),
                productId, "RM-101", "Raw Material 101",
                new BigDecimal("10"), new BigDecimal("25.00"), BigDecimal.ZERO
            ))
        ));

        bus.drain();

        PurchaseToPaySaga sagaInvoiceApproved = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaInvoiceApproved.state()).isEqualTo(PurchaseToPaySaga.SUPPLIER_INVOICE_APPROVED);

        // Step 6: pay. Need to know the supplier invoice id.
        UUID supplierInvoiceId = finance.supplierInvoices.findByStatus(SupplierInvoice.Status.APPROVED).get(0).id().value();
        // The harness doesn't model the maintain_allocation_totals trigger;
        // record the allocation manually so paidAmount matches before the
        // payment recordSupplierPayment validates it. Actually
        // recordSupplierPayment computes invoiceStatusAfter from the snapshot's
        // paidAmount + payment amount, so we don't need to pre-stamp.
        finance.paymentService.recordSupplierPayment(new RecordSupplierPaymentCommand(
            "PAY-SUP-001",
            supplierInvoiceId,
            new BigDecimal("250.00"),
            "bank_transfer",
            LocalDate.of(2026, 5, 20)
        ));

        bus.drain();

        PurchaseToPaySaga sagaCompleted = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaCompleted.state()).isEqualTo(PurchaseToPaySaga.COMPLETED);
        assertThat(purchasing.paymentProjection.isFullyPaid(po.id().value())).isTrue();

        // Cross-service event audit:
        assertThat(purchasing.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(PurchaseRequisitionCreated.EVENT_TYPE, PurchaseOrderCreated.EVENT_TYPE, PurchaseOrderApproved.EVENT_TYPE);
        assertThat(finance.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SupplierInvoiceApproved.EVENT_TYPE, SupplierPaymentMade.EVENT_TYPE);
    }

    private static PurchaseOrderId findCreatedPoId(PurchasingTestKit purchasing) {
        // The InMemoryOutboxPort exposes all outbox rows; find the PO by its
        // PurchaseOrderCreated event aggregate id.
        return purchasing.outbox.all().stream()
            .filter(r -> PurchaseOrderCreated.EVENT_TYPE.equals(r.getEventType()))
            .map(r -> PurchaseOrderId.of(r.getAggregateId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no PurchaseOrderCreated emitted"));
    }
}
