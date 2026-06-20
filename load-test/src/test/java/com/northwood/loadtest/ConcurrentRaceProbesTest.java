package com.northwood.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Focused concurrent race probes ({@code docs/concurrent-load-test.md} §4.6) —
 * deliberate two-worker collisions on a single aggregate, asserting the
 * exactly-once / no-half-state properties the high-volume stress run cannot pin
 * down. Each test sets up one order in the right state, fires two commands
 * <em>simultaneously</em> (a {@link CyclicBarrier} releases both threads at once),
 * and asserts the targeted property against the live DB.
 *
 * <p><b>LIVE-stack only</b> — not a self-contained CI test (same posture as
 * {@link OrderToCashSimulation}). Requires the running multi-service stack +
 * {@code provision-keycloak.ps1}. Run:
 * {@code mvn -Pload-test -pl load-test test -Dtest=ConcurrentRaceProbesTest}.
 *
 * <p>The guards under test are DB-enforced and row-serialized: the customer
 * invoice's {@code CHECK (paid_amount <= total_amount)} + the row-locking
 * {@code maintain_allocation_totals} trigger (double-pay), the stock balance's
 * {@code CHECK (reserved_quantity >= 0)} + atomic {@code col = col - ?} decrement
 * (double-ship), and {@code SalesOrder}'s {@code anyLineShipped()} cancel gate
 * (cancel-vs-ship).
 */
class ConcurrentRaceProbesTest {

    private static final String SALES = System.getProperty("sales.base", "http://localhost:8082");
    private static final String INVENTORY = System.getProperty("inventory.base", "http://localhost:8083");
    private static final String FINANCE = System.getProperty("finance.base", "http://localhost:8086");
    private static final String ISSUER = System.getProperty("keycloak.issuer", "http://localhost:8090/realms/northwood");
    private static final String CLIENT = System.getProperty("keycloak.client", "northwood-loadtest");
    private static final String JDBC_URL = System.getProperty("jdbc.url", "jdbc:postgresql://localhost:5432/northwood_erp");
    private static final String JDBC_USER = System.getProperty("jdbc.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("jdbc.password", "postgres");

    private static final String PRODUCT_ID = "00000000-0000-7000-8000-000000000400";
    private static final String PRODUCT_SKU = "FG-CHAIR-001";
    private static final String PRODUCT_NAME = "Wooden Dining Chair";
    private static final String UNIT_COST = "120";
    private static final String CUSTOMER_CODE = "CUST-001";

    // A buy-to-order product (replenishment_strategy = to_order, purchased): each
    // sales order raises a dedicated order-pegged purchase order. Used by the
    // multi-leg-compensation probe.
    private static final String TO_ORDER_PRODUCT_ID = "00000000-0000-7000-8000-000000000501";
    private static final String TO_ORDER_SKU = "FG-CARPET-001";
    private static final String TO_ORDER_NAME = "Custom-design Carpet";

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Token for a load user (clerk + warehouse + accountant) — drives place/ship/pay. */
    private static String loadToken;
    /** Token for the named sales_manager (sam) — the only role that may cancel an order. */
    private static String managerToken;

    @BeforeAll
    static void mintTokens() {
        loadToken = token("user-0", "password");
        managerToken = token("sam", "sam");
    }

    // ── TC-DOUBLE-PAY ─────────────────────────────────────────────────────
    @Test
    void twoConcurrentFullPayments_allocateExactlyOnce() throws Exception {
        Order order = placeAndReserve();
        ship(loadToken, order, "SHIP-" + order.number);
        Invoice invoice = awaitInvoice(order.id);

        String payBody = """
            {"paymentNumber":"%s","customerInvoiceHeaderId":"%s","amount":%s,"paymentMethod":"bank_transfer"}""";
        int[] codes = race(
            () -> post(FINANCE + "/api/payments/customer", loadToken,
                payBody.formatted("PAYA-" + order.number, invoice.id, invoice.total)),
            () -> post(FINANCE + "/api/payments/customer", loadToken,
                payBody.formatted("PAYB-" + order.number, invoice.id, invoice.total)));

        assertThat(count(codes, 201))
            .as("exactly one of two concurrent full payments may settle (codes=%s, %s)", codes[0], codes[1])
            .isEqualTo(1);
        assertThat(decimal("SELECT paid_amount FROM finance.customer_invoice_header WHERE customer_invoice_header_id=?", invoice.id))
            .as("paid_amount must equal the invoice total exactly — no double allocation")
            .isEqualByComparingTo(invoice.total);
        assertThat(string("SELECT status FROM finance.customer_invoice_header WHERE customer_invoice_header_id=?", invoice.id))
            .isEqualTo("paid");
    }

    // ── TC-DOUBLE-SHIP ────────────────────────────────────────────────────
    // Guards the over-ship fix: ShipmentService.post atomically claims each line's
    // ship quantity against its outstanding allowance (ordered − already shipped) in
    // inventory.sales_order_line_facts, row-locked, before any stock decrement. Two
    // concurrent shipments of one reserved line therefore can no longer both succeed —
    // the one that would push cumulative shipped past ordered is rejected (409), so
    // on_hand is decremented exactly once.
    @Test
    void twoConcurrentShipments_shipExactlyOnce() throws Exception {
        Order order = placeAndReserve();

        int[] codes = race(
            () -> post(INVENTORY + "/api/shipments", loadToken, shipBody(order, "SHIPA-" + order.number)),
            () -> post(INVENTORY + "/api/shipments", loadToken, shipBody(order, "SHIPB-" + order.number)));

        assertThat(count(codes, 201))
            .as("exactly one of two concurrent shipments of one reserved line may succeed (codes=%s, %s)", codes[0], codes[1])
            .isEqualTo(1);
        // The shipped-quantity update flows back to sales asynchronously; it must
        // settle to exactly the ordered quantity (1) — never 2 (a double-ship).
        assertThat(awaitLineShippedQuantity(order.lineId))
            .as("line shipped quantity must converge to 1, not 2")
            .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(count(codes, 201)).isEqualTo(1);
        assertNoNegativeStock();
    }

    // ── TC-CANCEL-SHIP ────────────────────────────────────────────────────
    // Guards the cancel-vs-ship fix: cancel is two-phase — sales only requests it,
    // and inventory arbitrates against any concurrent shipment on the same
    // sales_order_line_facts rows (the cancellation-claim flips cancelled WHERE
    // nothing shipped; the ship-claim refuses a cancelled line). Whichever commits
    // first wins, so the order is never both shipped and cancelled: cancel wins →
    // the ship is rejected and the order is cancelled; ship wins → no applied-ack is
    // sent, so the cancellation is dropped and the order proceeds as shipped.
    @Test
    void cancelRacingShipment_leavesNoHalfState() throws Exception {
        Order order = placeAndReserve();

        int[] codes = race(
            () -> post(SALES + "/api/sales-orders/" + order.id + "/cancel", managerToken,
                "{\"reason\":\"race probe\"}"),
            () -> post(INVENTORY + "/api/shipments", loadToken, shipBody(order, "SHIPC-" + order.number)));

        int cancelCode = codes[0];
        int shipCode = codes[1];
        // Let the winning effect settle back onto the order.
        sleep(4000);
        BigDecimal shipped = decimal("SELECT shipped_quantity FROM sales.sales_order_line WHERE sales_order_line_id=?", order.lineId);
        String status = string("SELECT status FROM sales.sales_order_header WHERE sales_order_header_id=?", order.id);

        // The forbidden half-state: goods shipped AND the order cancelled.
        assertThat(shipped.signum() > 0 && "cancelled".equals(status))
            .as("must never be both shipped (qty=%s) and cancelled (status=%s); cancel=%s ship=%s",
                shipped, status, cancelCode, shipCode)
            .isFalse();
        // Exactly one command may take effect on the aggregate.
        boolean shippedWon = shipCode == 201 && shipped.signum() > 0;
        boolean cancelWon = "cancelled".equals(status);
        assertThat(shippedWon ^ cancelWon)
            .as("exactly one of cancel/ship must win (shippedWon=%s cancelWon=%s status=%s)", shippedWon, cancelWon, status)
            .isTrue();
        assertNoNegativeStock();
    }

    // ── TC-COMPENSATE-PEGGED ──────────────────────────────────────────────
    // Exercises multi-leg compensation on the live stack: cancelling a to_order
    // line before its goods are received must WITHDRAW the committed order-pegged
    // purchase order, not orphan it. Sequential (not a barrier race) — the property
    // is the multi-leg drain reaching the right terminal, not a two-command
    // collision. The buy-to-order carpet raises a dedicated PO; once it is sent we
    // cancel and assert: the PO flips to 'cancelled', the replenishment is
    // 'cancelled' (no orphan), and the fulfilment saga reaches 'compensated' with
    // the order header 'cancelled'.
    @Test
    void cancellingAToOrderOrder_withdrawsThePeggedPurchaseOrder() throws Exception {
        Order order = placeToOrder();
        String purchaseOrderId = awaitDispatchedPeggedPo(order.id);

        int cancelCode = post(SALES + "/api/sales-orders/" + order.id + "/cancel", managerToken,
            "{\"reason\":\"compensation probe\"}");
        assertThat(cancelCode)
            .as("cancel of a pre-shipment to_order line is accepted (202)")
            .isEqualTo(202);

        // The compensation fans out (inventory → purchasing) + drains asynchronously.
        String poStatus = awaitString(
            "SELECT status FROM purchasing.purchase_order_header WHERE purchase_order_header_id=?",
            purchaseOrderId, "cancelled");
        assertThat(poStatus)
            .as("the order-pegged purchase order must be withdrawn, not orphaned")
            .isEqualTo("cancelled");

        String sagaState = awaitString(
            "SELECT saga_state FROM sales.sales_order_fulfilment_saga WHERE sales_order_header_id=?",
            order.id, "compensated");
        assertThat(sagaState)
            .as("the fulfilment saga must drain its purchasing leg and reach 'compensated'")
            .isEqualTo("compensated");
        assertThat(string("SELECT status FROM sales.sales_order_header WHERE sales_order_header_id=?", order.id))
            .as("order header must be cancelled")
            .isEqualTo("cancelled");
        // No orphan: the order-pegged replenishment must itself be cancelled, never
        // left 'dispatched' pointing at a withdrawn PO.
        assertThat(string("SELECT status FROM inventory.replenishment_request WHERE source_sales_order_header_id=? AND reason='order_pegged'", order.id))
            .as("the order-pegged replenishment must be cancelled (no orphan dispatched row)")
            .isEqualTo("cancelled");
        assertNoNegativeStock();
    }

    // ── TC-PAY-FIRST ──────────────────────────────────────────────────────
    // Completion gate (docs/concurrent-load-test.md §4.6 / §6 invariant 1) for the
    // pay-before-ship pattern. A prepayment order gates at awaiting_prepayment: its
    // line does not reserve and the warehouse cannot ship until the up-front invoice
    // is settled. Once paid, the saga walks prepaid → reserved → supply_secured and
    // posting the shipment latches the order straight to completed (no commercial
    // invoice / second payment to wait on). Sequential — the property is the gate
    // ordering, not a two-command collision.
    @Test
    void prepaidOrder_cannotShipUntilPaid_thenCompletes() throws Exception {
        Order order = placePrepayment();
        Invoice prepayment = awaitPrepaymentInvoice(order.id);

        // Gate: the up-front invoice is unpaid, so the line never reserved and the
        // shipment must be refused.
        int earlyShip = ship(loadToken, order, "SHIP-EARLY-" + order.number);
        assertThat(earlyShip)
            .as("shipment must be refused before the prepayment lands (got %s)", earlyShip)
            .isGreaterThanOrEqualTo(400);

        int payCode = post(FINANCE + "/api/payments/customer", loadToken,
            """
            {"paymentNumber":"PREPAY-%s","customerInvoiceHeaderId":"%s","amount":%s,"paymentMethod":"bank_transfer"}"""
                .formatted(order.number, prepayment.id, prepayment.total.toPlainString()));
        assertThat(payCode).as("prepayment payment accepted").isEqualTo(201);

        // Gate releases: the saga settles the deposit, reserves, and is shippable.
        awaitReserved(order.id);
        int shipCode = ship(loadToken, order, "SHIP-" + order.number);
        assertThat(shipCode).as("shipment accepted once prepaid + reserved").isEqualTo(201);

        String saga = awaitString(
            "SELECT saga_state FROM sales.sales_order_fulfilment_saga WHERE sales_order_header_id=?",
            order.id, "completed");
        assertThat(saga).as("a prepaid order completes once shipped (no further payment)").isEqualTo("completed");
        assertNoNegativeStock();
    }

    // ── TC-PARTIAL-SHIP ───────────────────────────────────────────────────
    // Line-fold rollup (docs/concurrent-load-test.md §4.6) — conservation of shipped
    // quantity + the composed-state-machine ship band. A 2-qty line, fully reserved,
    // shipped in two halves: cumulative shipped_quantity must fold 0 → 1 → 2 and the
    // line status walk reserved → partially_shipped → shipped, never over-shipping
    // past ordered (the DB CHECK shipped_quantity <= ordered_quantity is the floor).
    @Test
    void partialThenFinalShipment_lineFoldRollsUp() throws Exception {
        Order order = placeAndReserveQty(2);

        assertThat(shipQty(loadToken, order, "SHIPP1-" + order.number, 1))
            .as("first partial shipment (1 of 2) accepted").isEqualTo(201);
        assertThat(awaitLineShipped(order.lineId, BigDecimal.ONE))
            .as("cumulative shipped folds to 1").isEqualByComparingTo(BigDecimal.ONE);
        assertThat(awaitString("SELECT line_status FROM sales.sales_order_line WHERE sales_order_line_id=?",
            order.lineId, "partially_shipped"))
            .as("line is partially_shipped after the first half").isEqualTo("partially_shipped");

        assertThat(shipQty(loadToken, order, "SHIPP2-" + order.number, 1))
            .as("final shipment (2 of 2) accepted").isEqualTo(201);
        assertThat(awaitLineShipped(order.lineId, new BigDecimal("2")))
            .as("cumulative shipped folds to 2, never past ordered").isEqualByComparingTo(new BigDecimal("2"));
        assertThat(awaitString("SELECT line_status FROM sales.sales_order_line WHERE sales_order_line_id=?",
            order.lineId, "shipped"))
            .as("line is shipped once fully dispatched").isEqualTo("shipped");
        assertNoNegativeStock();
    }

    // ── TC-SUPPLY-DUP ─────────────────────────────────────────────────────
    // Idempotency / single-top-up on the supply side (docs/concurrent-load-test.md
    // §4.6). Two concurrent goods-receipts for the FULL pegged quantity of one
    // buy-to-order PO line must top up on-hand exactly once: the order-pegged
    // replenishment is raised for the exact line qty and reserved straight to the SO
    // line, so a second full receipt crediting the excess would orphan it in free ATP
    // (dead stock no order can reserve). Verified green (3× under the barrier race):
    // exactly one receipt succeeds and on-hand rises by the peg qty, not 2× — the
    // to-order over-receipt guard + the received_quantity ≤ ordered enforcement hold
    // under the collision (the loser is rejected before any stock credit). Unlike
    // TC-DOUBLE-PAY this guard is not a single DB CHECK, so the live collision is the
    // evidence it holds end-to-end.
    @Test
    void twoConcurrentGoodsReceipts_topUpExactlyOnce() throws Exception {
        Order order = placeToOrder();
        PeggedPo po = awaitPeggedPoForReceipt(order.id);
        BigDecimal before = onHand(TO_ORDER_PRODUCT_ID);

        int[] codes = race(
            () -> post(INVENTORY + "/api/goods-receipts", loadToken, goodsReceiptBody(po, "GRA-" + order.number)),
            () -> post(INVENTORY + "/api/goods-receipts", loadToken, goodsReceiptBody(po, "GRB-" + order.number)));

        assertThat(count(codes, 201))
            .as("exactly one of two concurrent full goods-receipts on a pegged PO line may top up (codes=%s, %s)", codes[0], codes[1])
            .isEqualTo(1);
        assertThat(onHand(TO_ORDER_PRODUCT_ID).subtract(before))
            .as("pegged on-hand topped up exactly once (peg qty), never doubled into orphaned ATP")
            .isEqualByComparingTo(po.qty);
        assertNoNegativeStock();
    }

    // ── flow helpers ──────────────────────────────────────────────────────

    private record Order(String id, String number, String lineId, String customerId, String customerName) {}

    private record Invoice(String id, BigDecimal total) {}

    private Order placeAndReserve() throws Exception {
        String number = "PROBE-" + System.nanoTime();
        String body = """
            {"orderNumber":"%s","customerCode":"%s","currencyCode":"AUD","paymentTerms":"on_shipment",
             "lines":[{"productId":"%s","productSku":"%s","productName":"%s","orderedQuantity":1}]}"""
            .formatted(number, CUSTOMER_CODE, PRODUCT_ID, PRODUCT_SKU, PRODUCT_NAME);
        HttpResponse<String> resp = send(SALES + "/api/sales-orders", loadToken, body);
        assertThat(resp.statusCode()).as("place order: %s", resp.body()).isEqualTo(201);
        String json = resp.body();
        Order order = new Order(
            extract(json, "\"id\"\\s*:\\s*\"([^\"]+)\""),
            number,
            extract(json, "\"lineId\"\\s*:\\s*\"([^\"]+)\""),
            extract(json, "\"customerId\"\\s*:\\s*\"([^\"]+)\""),
            extract(json, "\"customerName\"\\s*:\\s*\"([^\"]*)\""));
        awaitReserved(order.id);
        return order;
    }

    /**
     * Place a buy-to-order line (no stock) so the fulfilment saga pegs it and
     * inventory raises a dedicated order-pegged replenishment. Does NOT await
     * 'reserved' (it never reserves — it parks at stock_reservation_incomplete
     * awaiting its dedicated PO).
     */
    private Order placeToOrder() throws Exception {
        String number = "PROBE-CMP-" + System.nanoTime();
        String body = """
            {"orderNumber":"%s","customerCode":"%s","currencyCode":"AUD","paymentTerms":"on_shipment",
             "lines":[{"productId":"%s","productSku":"%s","productName":"%s","orderedQuantity":1}]}"""
            .formatted(number, CUSTOMER_CODE, TO_ORDER_PRODUCT_ID, TO_ORDER_SKU, TO_ORDER_NAME);
        HttpResponse<String> resp = send(SALES + "/api/sales-orders", loadToken, body);
        assertThat(resp.statusCode()).as("place to_order order: %s", resp.body()).isEqualTo(201);
        String json = resp.body();
        return new Order(
            extract(json, "\"id\"\\s*:\\s*\"([^\"]+)\""),
            number,
            extract(json, "\"lineId\"\\s*:\\s*\"([^\"]+)\""),
            extract(json, "\"customerId\"\\s*:\\s*\"([^\"]+)\""),
            extract(json, "\"customerName\"\\s*:\\s*\"([^\"]*)\""));
    }

    /**
     * Wait for the order's order-pegged replenishment to be dispatched to purchasing
     * and linked to a created (sent) purchase order; returns that PO id.
     */
    private String awaitDispatchedPeggedPo(String orderId) throws Exception {
        for (int i = 0; i < 60; i++) {
            try (Connection c = jdbc();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT linked_purchase_order_id FROM inventory.replenishment_request "
                         + "WHERE source_sales_order_header_id=? AND reason='order_pegged' AND linked_purchase_order_id IS NOT NULL")) {
                ps.setObject(1, java.util.UUID.fromString(orderId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getString(1) != null) {
                        return rs.getString(1);
                    }
                }
            }
            sleep(1000);
        }
        throw new IllegalStateException("order " + orderId + " never got a linked order-pegged purchase order");
    }

    /** Poll a single-column string query until it equals {@code expected} (or give up and return the last value). */
    private String awaitString(String sql, String uuidParam, String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 40; i++) {
            last = string(sql, uuidParam);
            if (expected.equals(last)) {
                return last;
            }
            sleep(1000);
        }
        return last;
    }

    private String shipBody(Order order, String shipmentNumber) {
        return """
            {"shipmentNumber":"%s","salesOrderHeaderId":"%s","salesOrderNumber":"%s",
             "customerId":"%s","customerName":"%s","warehouseCode":"MAIN",
             "lines":[{"salesOrderLineId":"%s","productId":"%s","productSku":"%s","productName":"%s","shippedQuantity":1,"unitCost":%s}]}"""
            .formatted(shipmentNumber, order.id, order.number, order.customerId, order.customerName,
                order.lineId, PRODUCT_ID, PRODUCT_SKU, PRODUCT_NAME, UNIT_COST);
    }

    private int ship(String token, Order order, String shipmentNumber) throws Exception {
        return post(INVENTORY + "/api/shipments", token, shipBody(order, shipmentNumber));
    }

    private void awaitReserved(String orderId) throws Exception {
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> r = HTTP.send(get(SALES + "/api/sales-orders/" + orderId, loadToken), HttpResponse.BodyHandlers.ofString());
            if ("reserved".equals(extract(r.body(), "\"status\"\\s*:\\s*\"([^\"]+)\""))) {
                return;
            }
            sleep(1000);
        }
        throw new IllegalStateException("order " + orderId + " never reached 'reserved'");
    }

    private Invoice awaitInvoice(String orderId) throws Exception {
        for (int i = 0; i < 40; i++) {
            try (Connection c = jdbc();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT customer_invoice_header_id, total_amount FROM finance.customer_invoice_header WHERE sales_order_header_id=?")) {
                ps.setObject(1, java.util.UUID.fromString(orderId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Invoice(rs.getString(1), rs.getBigDecimal(2));
                    }
                }
            }
            sleep(1000);
        }
        throw new IllegalStateException("no commercial invoice for order " + orderId);
    }

    private BigDecimal awaitLineShippedQuantity(String lineId) throws Exception {
        BigDecimal last = BigDecimal.ZERO;
        for (int i = 0; i < 20; i++) {
            last = decimal("SELECT shipped_quantity FROM sales.sales_order_line WHERE sales_order_line_id=?", lineId);
            if (last.compareTo(BigDecimal.ONE) >= 0) {
                return last;
            }
            sleep(1000);
        }
        return last;
    }

    /** Poll a line's cumulative shipped_quantity until it reaches {@code target} (or give up). */
    private BigDecimal awaitLineShipped(String lineId, BigDecimal target) throws Exception {
        BigDecimal last = BigDecimal.ZERO;
        for (int i = 0; i < 30; i++) {
            last = decimal("SELECT shipped_quantity FROM sales.sales_order_line WHERE sales_order_line_id=?", lineId);
            if (last.compareTo(target) >= 0) {
                return last;
            }
            sleep(1000);
        }
        return last;
    }

    /** Place a prepayment (cash-with-order) line; the saga gates at awaiting_prepayment. */
    private Order placePrepayment() throws Exception {
        String number = "PROBE-PRE-" + System.nanoTime();
        String body = """
            {"orderNumber":"%s","customerCode":"%s","currencyCode":"AUD","paymentTerms":"prepayment",
             "lines":[{"productId":"%s","productSku":"%s","productName":"%s","orderedQuantity":1}]}"""
            .formatted(number, CUSTOMER_CODE, PRODUCT_ID, PRODUCT_SKU, PRODUCT_NAME);
        HttpResponse<String> resp = send(SALES + "/api/sales-orders", loadToken, body);
        assertThat(resp.statusCode()).as("place prepayment order: %s", resp.body()).isEqualTo(201);
        return orderFrom(resp.body(), number);
    }

    /** Place an N-qty single-line on_shipment order and wait until it is fully reserved. */
    private Order placeAndReserveQty(int qty) throws Exception {
        String number = "PROBE-Q" + qty + "-" + System.nanoTime();
        String body = """
            {"orderNumber":"%s","customerCode":"%s","currencyCode":"AUD","paymentTerms":"on_shipment",
             "lines":[{"productId":"%s","productSku":"%s","productName":"%s","orderedQuantity":%d}]}"""
            .formatted(number, CUSTOMER_CODE, PRODUCT_ID, PRODUCT_SKU, PRODUCT_NAME, qty);
        HttpResponse<String> resp = send(SALES + "/api/sales-orders", loadToken, body);
        assertThat(resp.statusCode()).as("place qty-%d order: %s", qty, resp.body()).isEqualTo(201);
        Order order = orderFrom(resp.body(), number);
        awaitReserved(order.id);
        return order;
    }

    private Order orderFrom(String json, String number) {
        return new Order(
            extract(json, "\"id\"\\s*:\\s*\"([^\"]+)\""),
            number,
            extract(json, "\"lineId\"\\s*:\\s*\"([^\"]+)\""),
            extract(json, "\"customerId\"\\s*:\\s*\"([^\"]+)\""),
            extract(json, "\"customerName\"\\s*:\\s*\"([^\"]*)\""));
    }

    /** Wait for finance to raise the up-front (prepayment) invoice for the order. */
    private Invoice awaitPrepaymentInvoice(String orderId) throws Exception {
        for (int i = 0; i < 40; i++) {
            try (Connection c = jdbc();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT customer_invoice_header_id, total_amount FROM finance.customer_invoice_header "
                         + "WHERE sales_order_header_id=? AND invoice_type='prepayment'")) {
                ps.setObject(1, java.util.UUID.fromString(orderId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Invoice(rs.getString(1), rs.getBigDecimal(2));
                    }
                }
            }
            sleep(1000);
        }
        throw new IllegalStateException("no prepayment invoice for order " + orderId);
    }

    private String shipQtyBody(Order order, String shipmentNumber, int qty) {
        return """
            {"shipmentNumber":"%s","salesOrderHeaderId":"%s","salesOrderNumber":"%s",
             "customerId":"%s","customerName":"%s","warehouseCode":"MAIN",
             "lines":[{"salesOrderLineId":"%s","productId":"%s","productSku":"%s","productName":"%s","shippedQuantity":%d,"unitCost":%s}]}"""
            .formatted(shipmentNumber, order.id, order.number, order.customerId, order.customerName,
                order.lineId, PRODUCT_ID, PRODUCT_SKU, PRODUCT_NAME, qty, UNIT_COST);
    }

    private int shipQty(String token, Order order, String shipmentNumber, int qty) throws Exception {
        return post(INVENTORY + "/api/shipments", token, shipQtyBody(order, shipmentNumber, qty));
    }

    private record PeggedPo(String poId, String poNumber, String supplierId, String supplierName,
                            String lineId, String productId, String productSku, String productName,
                            BigDecimal qty, BigDecimal unitPrice) {}

    /** Wait for the order's pegged PO to be created, then read its sole line for a goods-receipt. */
    private PeggedPo awaitPeggedPoForReceipt(String orderId) throws Exception {
        String poId = awaitDispatchedPeggedPo(orderId);
        for (int i = 0; i < 30; i++) {
            try (Connection c = jdbc();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT h.purchase_order_number, h.supplier_id, h.supplier_name, "
                         + "l.purchase_order_line_id, l.product_id, l.product_sku, l.product_name, "
                         + "l.ordered_quantity, l.unit_price "
                         + "FROM purchasing.purchase_order_header h "
                         + "JOIN purchasing.purchase_order_line l ON l.purchase_order_header_id = h.purchase_order_header_id "
                         + "WHERE h.purchase_order_header_id = ?")) {
                ps.setObject(1, java.util.UUID.fromString(poId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PeggedPo(poId, rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9));
                    }
                }
            }
            sleep(1000);
        }
        throw new IllegalStateException("no PO line for the pegged PO of order " + orderId);
    }

    private String goodsReceiptBody(PeggedPo po, String grNumber) {
        return """
            {"goodsReceiptNumber":"%s","purchaseOrderHeaderId":"%s","purchaseOrderNumber":"%s",
             "supplierId":"%s","supplierName":"%s","warehouseCode":"MAIN",
             "lines":[{"purchaseOrderLineId":"%s","productId":"%s","productSku":"%s","productName":"%s","receivedQuantity":%s,"unitCost":%s}]}"""
            .formatted(grNumber, po.poId, po.poNumber, po.supplierId, esc(po.supplierName),
                po.lineId, po.productId, po.productSku, esc(po.productName), po.qty.toPlainString(), po.unitPrice.toPlainString());
    }

    /** Current on-hand for a product at the MAIN warehouse. */
    private BigDecimal onHand(String productId) throws Exception {
        return decimal("SELECT on_hand_quantity FROM inventory.stock_balance "
            + "WHERE product_id=? AND warehouse_id='00000000-0000-7000-8000-000000000020'", productId);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── concurrency + HTTP + JDBC primitives ──────────────────────────────

    private int[] race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<Integer> fa = pool.submit(() -> { barrier.await(); return a.call(); });
        Future<Integer> fb = pool.submit(() -> { barrier.await(); return b.call(); });
        try {
            return new int[] {fa.get(), fb.get()};
        } finally {
            pool.shutdownNow();
        }
    }

    private static int post(String url, String token, String body) throws Exception {
        return send(url, token, body).statusCode();
    }

    private static HttpResponse<String> send(String url, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest get(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET().build();
    }

    private static int count(int[] codes, int target) {
        int n = 0;
        for (int c : codes) {
            if (c == target) {
                n++;
            }
        }
        return n;
    }

    private void assertNoNegativeStock() throws Exception {
        assertThat(decimal("SELECT count(*) FROM inventory.stock_balance WHERE on_hand_quantity<0 OR reserved_quantity<0 OR available_quantity<0", null))
            .as("no stock_balance row may go negative")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static Connection jdbc() throws Exception {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    private BigDecimal decimal(String sql, String uuidParam) throws Exception {
        try (Connection c = jdbc(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (uuidParam != null) {
                ps.setObject(1, java.util.UUID.fromString(uuidParam));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBigDecimal(1) != null ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private String string(String sql, String uuidParam) throws Exception {
        try (Connection c = jdbc(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, java.util.UUID.fromString(uuidParam));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String token(String username, String password) {
        String form = "grant_type=password&client_id=" + CLIENT + "&username=" + username + "&password=" + password;
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(
                    URI.create(ISSUER.replaceAll("/+$", "") + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            Matcher m = ACCESS_TOKEN.matcher(resp.body());
            if (resp.statusCode() != 200 || !m.find()) {
                throw new IllegalStateException("token for " + username + ": HTTP " + resp.statusCode() + " " + resp.body());
            }
            return m.group(1);
        } catch (Exception e) {
            throw new IllegalStateException("token request for " + username + " failed", e);
        }
    }

    private static String extract(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no match for " + regex + " in " + json);
        }
        return m.group(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
