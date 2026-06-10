package com.northwood.testharness.p2p;

import static com.northwood.testharness.dsl.Dsl.a_purchasable_product;
import static com.northwood.testharness.dsl.Dsl.a_purchase_order_for;
import static com.northwood.testharness.dsl.Dsl.a_reviewer;
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
import com.northwood.finance.domain.events.SupplierInvoiceRejected;
import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import org.junit.jupiter.api.Test;

/**
 * Procure-to-pay rejection path, ported from {@code PurchaseToPayRejectionPathTest}
 * (REQ-PUR-050, REQ-FIN-051). A PO is approved and received, then a supplier
 * invoice arrives outside the 2% price tolerance, parks at
 * {@code three_way_match_failed}, is manually rejected by a reviewer, and the
 * purchase-to-pay saga lands in terminal {@code failed} with the invoice cancelled.
 *
 * <p>Drives the real {@code GoodsReceiptService}; the three-way-match failure is
 * real (a price 20% over the PO line price).
 */
class PurchaseToPayRejectionPathDslTest {

    @Test
    void supplier_invoice_outside_tolerance_is_rejected_and_the_saga_fails() {
        scenario("supplier invoice outside the price tolerance is rejected: the saga fails")

            // ── guard: a purchasable raw material at $25 ──
            .given(a_purchasable_product("RM-201", "Raw Material 201").suppliedAt(money(25)))

            // ── PR → PO → approve → receive ──
            .when(buyer().raises_requisition("PR-2001").line("RM-201", qty(10)))
            .when(buyer().approves_the_po_for("PR-2001"))
            .then(a_purchase_order_for("PR-2001").reaches(PurchaseToPaySaga.WAITING_FOR_GOODS))
            .when(warehouse(MAIN).receives_goods_for("PR-2001"))
            .then(a_purchase_order_for("PR-2001").reaches(PurchaseToPaySaga.GOODS_RECEIVED))

            // ── trigger: supplier invoices at $30 (20% over the $25 PO price) — match fails ──
            .when(the_supplier().invoices("SI-201").for_requisition("PR-2001").at_unit_price(money(30)))
            .then(a_supplier_invoice().reaches(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED))
            .and(a_purchase_order_for("PR-2001").reaches(PurchaseToPaySaga.GOODS_RECEIVED))

            // ── trigger: a reviewer rejects the parked invoice ──
            .when(a_reviewer().rejects_the_supplier_invoice().because("unit price 20% over the PO"))
            // ── outcome: the saga fails terminally and the invoice is cancelled ──
            .then(a_purchase_order_for("PR-2001").reaches(PurchaseToPaySaga.FAILED))
            .and(a_supplier_invoice().reaches(SupplierInvoice.Status.CANCELLED))
            .and(events_published(SupplierInvoiceRejected.EVENT_TYPE));
    }
}
