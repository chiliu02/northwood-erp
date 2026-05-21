package com.northwood.testharness.p2p;

import com.northwood.finance.application.dto.RecordSupplierInvoiceCommand;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.events.SupplierInvoiceRejected;
import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.purchasing.application.dto.CreateRequisitionCommand;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderLine;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.FinanceTestKit;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.PurchasingTestKit;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.B23 — purchase-to-pay rejection path E2E test.
 *
 * <p>Walks the saga to {@code goods_received}, then submits a supplier
 * invoice with a per-line unit price outside the 2% variance tolerance.
 * The invoice parks at {@code three_way_match_failed} and the saga stays
 * at {@code goods_received}. Olivia rejects the parked invoice via
 * {@code SupplierInvoiceService.manualReject}, which emits
 * {@code finance.SupplierInvoiceRejected}; the inbox handler lands the
 * saga in terminal {@code failed}.
 */
class PurchaseToPayRejectionPathTest {

    @Test
    void parked_invoice_rejection_lands_saga_in_failed() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        PurchasingTestKit purchasing = new PurchasingTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        UUID productId = UUID.randomUUID();
        SupplierId supplierId = purchasing.suppliers.findByCode("SUP-001").orElseThrow().id();
        purchasing.priceLookup.put(supplierId.value(), productId, Currencies.AUD, new BigDecimal("25.00"));

        // Step 1: PR → PO at draft.
        purchasing.requisitionService.createManual(new CreateRequisitionCommand(
            "PR-REJ-001",
            "tom@example.com",
            List.of(new RequisitionLineRequest(
                productId, "RM-201", "Raw Material 201",
                new BigDecimal("10"), null
            ))
        ));
        PurchaseOrder po = purchasing.orders.findById(findCreatedPoId(purchasing)).orElseThrow();
        assertThat(po.status()).isEqualTo(PurchaseOrder.Status.DRAFT);

        // Step 2: approve + drive worker to waiting_for_goods.
        purchasing.purchaseOrderService.approve(po.id().value(), "tom@example.com", "ok to send");
        purchasing.advanceSagaWorker();
        bus.drain();

        // Step 3: goods receipt.
        PurchaseOrderLine poLine = po.lines().get(0);
        UUID receiptHeaderId = UUID.randomUUID();
        UUID receiptEventId = UUID.randomUUID();
        GoodsReceived receipt = new GoodsReceived(
            receiptEventId, receiptHeaderId, "GR-REJ-001",
            po.id().value(), InventoryTestKit.DEFAULT_WAREHOUSE_ID, WarehouseCodes.MAIN,
            List.of(new GoodsReceived.ReceivedLine(
                UUID.randomUUID(), poLine.id(),
                productId, "RM-201", "Raw Material 201",
                new BigDecimal("10"), new BigDecimal("25.00")
            )),
            Instant.now()
        );
        inventory.outbox.appendPending(OutboxRow.pending(
            receiptEventId, InventoryAggregateTypes.GOODS_RECEIPT, receiptHeaderId,
            GoodsReceived.EVENT_TYPE, 1,
            json.writeValueAsString(receipt),
            null, null, null, null
        ));
        bus.drain();

        PurchaseToPaySaga sagaReceived = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaReceived.state()).isEqualTo(PurchaseToPaySaga.GOODS_RECEIVED);

        // Step 4: supplier invoice at unit price 50.00 vs PO line 25.00 — 100%
        // variance, far outside the 2% default tolerance. Invoice parks.
        finance.supplierInvoiceService.recordInvoice(new RecordSupplierInvoiceCommand(
            "SI-INT-REJ-001",
            "INV-FROM-SUP-REJ-001",
            po.id().value(),
            receiptHeaderId,
            supplierId.value(),
            "SUP-001",
            "Default Supplier",
            Currencies.AUD,
            List.of(new RecordSupplierInvoiceCommand.Line(
                poLine.id(), UUID.randomUUID(),
                productId, "RM-201", "Raw Material 201",
                new BigDecimal("10"), new BigDecimal("50.00"), BigDecimal.ZERO
            ))
        ));
        bus.drain();

        // Invoice is parked, saga unchanged.
        var parked = finance.supplierInvoices.findByStatus(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
        assertThat(parked).hasSize(1);
        PurchaseToPaySaga sagaStillReceived = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaStillReceived.state()).isEqualTo(PurchaseToPaySaga.GOODS_RECEIVED);

        // Step 5: reviewer rejects the parked invoice. Emits SupplierInvoiceRejected;
        // saga handler advances goods_received → failed.
        UUID supplierInvoiceId = parked.get(0).id().value();
        finance.supplierInvoiceService.manualReject(supplierInvoiceId, "olivia@example.com",
            "supplier sent the wrong invoice");
        bus.drain();

        PurchaseToPaySaga sagaFailed = purchasing.sagas.findByPurchaseOrderId(po.id().value()).orElseThrow();
        assertThat(sagaFailed.state()).isEqualTo(PurchaseToPaySaga.FAILED);
        assertThat(sagaFailed.currentStep()).isEqualTo("supplier_invoice_rejected");

        // Cross-service event audit:
        assertThat(finance.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SupplierInvoiceRejected.EVENT_TYPE);

        // Invoice is terminal cancelled.
        assertThat(finance.supplierInvoices.findAll().get(0).status()).isEqualTo(SupplierInvoice.Status.CANCELLED);
    }

    private static PurchaseOrderId findCreatedPoId(PurchasingTestKit purchasing) {
        return purchasing.outbox.all().stream()
            .filter(r -> PurchaseOrderCreated.EVENT_TYPE.equals(r.getEventType()))
            .map(r -> PurchaseOrderId.of(r.getAggregateId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no PurchaseOrderCreated emitted"));
    }
}
