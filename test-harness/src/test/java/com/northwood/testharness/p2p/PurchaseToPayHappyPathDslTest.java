package com.northwood.testharness.p2p;

import static com.northwood.testharness.dsl.Dsl.a_purchasable_product;
import static com.northwood.testharness.dsl.Dsl.a_purchase_order_for;
import static com.northwood.testharness.dsl.Dsl.a_supplier_invoice;
import static com.northwood.testharness.dsl.Dsl.buyer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.the_supplier;
import static com.northwood.testharness.dsl.Dsl.warehouse;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;

import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import org.junit.jupiter.api.Test;

/**
 * Procure-to-pay happy path, ported from {@code PurchaseToPayHappyPathTest}
 * (REQ-XBC-020, REQ-PUR-020/030/031, REQ-FIN-020/021/022). A manual requisition
 * auto-converts to a draft PO; the buyer approves it; goods are received; finance
 * 3-way-matches and approves the supplier invoice; the supplier is paid — walking
 * the purchase-to-pay saga started → purchase_order_approved → waiting_for_goods →
 * goods_received → supplier_invoice_approved → completed.
 *
 * <p>Drives the real {@code GoodsReceiptService} (the imperative twin forges the
 * {@code GoodsReceived} event).
 */
class PurchaseToPayHappyPathDslTest {

    @Test
    void manual_requisition_to_completed_purchase_to_pay() {
        scenario("manual requisition to completed: PR → PO → approve → receive → invoice → pay")

            // ── guard: a purchasable raw material at $25 from the default supplier ──
            .given(a_purchasable_product("RM-101", "Raw Material 101").suppliedAt(money(25)))

            // ── trigger: buyer raises a manual requisition for 10 (auto-converts to a draft PO) ──
            .when(buyer().raises_requisition("PR-1001").line("RM-101", qty(10)))
            .then(a_purchase_order_for("PR-1001").reaches(PurchaseToPaySaga.STARTED))

            // ── trigger: buyer approves the PO ──
            .when(buyer().approves_the_po_for("PR-1001"))
            .then(a_purchase_order_for("PR-1001").reaches(PurchaseToPaySaga.WAITING_FOR_GOODS))

            // ── trigger: the warehouse receives the goods ──
            .when(warehouse(MAIN).receives_goods_for("PR-1001"))
            .then(a_purchase_order_for("PR-1001").reaches(PurchaseToPaySaga.GOODS_RECEIVED))

            // ── trigger: supplier invoices at the PO price — 3-way match passes ──
            .when(the_supplier().invoices("SI-001").for_requisition("PR-1001").at_unit_price(money(25)))
            .then(a_purchase_order_for("PR-1001").reaches(PurchaseToPaySaga.SUPPLIER_INVOICE_APPROVED))
            .and(a_supplier_invoice().reaches(SupplierInvoice.Status.APPROVED))

            // ── trigger: supplier is paid in full ──
            .when(the_supplier().is_paid("PAY-001").of(money(250)))
            .then(a_purchase_order_for("PR-1001").reaches(PurchaseToPaySaga.COMPLETED))
            .and(a_purchase_order_for("PR-1001").is_fully_paid())
            .and(events_published(
                PurchaseRequisitionCreated.EVENT_TYPE,
                PurchaseOrderCreated.EVENT_TYPE,
                PurchaseOrderApproved.EVENT_TYPE,
                SupplierInvoiceApproved.EVENT_TYPE,
                SupplierPaymentMade.EVENT_TYPE));
    }
}
