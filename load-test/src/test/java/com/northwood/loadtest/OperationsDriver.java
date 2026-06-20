package com.northwood.loadtest;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supply-side <strong>operations driver</strong> for the concurrent load test
 * ({@code docs/concurrent-load-test.md} §7). The customer scenario
 * ({@link OrderToCashSimulation}) drives only the customer-forward path
 * (place → reserve → ship → invoice → pay); against <em>undersized</em> stock or
 * {@code to_order} SKUs that path parks at {@code stock_reservation_incomplete}
 * awaiting supply. This driver plays the warehouse / production actor that supplies
 * it, so the full
 * {@code shortage → ReplenishmentRequest → PO/WO → goods-receipt/WO-completion →
 * retry-reserve → ship} loop closes under live REST load.
 *
 * <p>Two drains, polled in a loop until stopped:
 * <ul>
 *   <li><b>Goods receipt</b> — for every {@code sent}/{@code partially_received}
 *       purchase order with an outstanding line, post {@code POST /api/goods-receipts}
 *       (warehouse_clerk). A receipt credits on-hand and fulfils the linked
 *       replenishment, un-parking the buy-to-order / purchased-shortage saga.</li>
 *   <li><b>Work-order completion</b> — for every {@code released}/{@code in_progress}
 *       work order, complete its next planned operation
 *       ({@code POST /api/work-orders/{id}/operations/{seq}/complete}, production_planner).
 *       The server enforces operation ordering + parent-on-children gating + auto
 *       WO-completion; completing the last operation of a WO (and, for a parent, of
 *       its children) fulfils its replenishment.</li>
 * </ul>
 *
 * <p><b>Discovery is by JDBC</b> (there is no cross-service "open work" REST list),
 * the same direct-DB read the focused probes use; the <b>actions are REST</b> against
 * the live services, so the real saga/handler round-trips run. Each drain tolerates
 * per-item failures (a parent op gated on pending children, a redelivery) and retries
 * on the next poll — the loop is convergent, not transactional.
 *
 * <p><b>LIVE-stack only.</b> Run alongside {@link OrderToCashSimulation} (it is also
 * run alongside {@link OrderToCashSimulation}). Standalone:
 * {@code mvn -Pload-test -pl load-test exec:java
 * -Dexec.mainClass=com.northwood.loadtest.OperationsDriver -Dexec.classpathScope=test
 * -Dexec.args="180"} (drain for 180s). Needs an operator identity carrying <b>warehouse_clerk +
 * production_planner</b> — the load users do once {@code provision-keycloak.ps1} adds
 * production_planner to their bundle.
 */
public final class OperationsDriver {

    private static final String INVENTORY = System.getProperty("inventory.base", "http://localhost:8083");
    private static final String MANUFACTURING = System.getProperty("manufacturing.base", "http://localhost:8084");
    private static final String ISSUER = System.getProperty("keycloak.issuer", "http://localhost:8090/realms/northwood");
    private static final String CLIENT = System.getProperty("keycloak.client", "northwood-loadtest");
    private static final String JDBC_URL = System.getProperty("jdbc.url", "jdbc:postgresql://localhost:5432/northwood_erp");
    private static final String JDBC_USER = System.getProperty("jdbc.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("jdbc.password", "postgres");
    private static final String OPERATOR_USER = System.getProperty("operator.user", "user-0");
    private static final String OPERATOR_PASSWORD = System.getProperty("keycloak.password", "password");
    private static final long POLL_MILLIS = Long.getLong("operations.pollMillis", 1500L);

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String token;

    public OperationsDriver(String token) {
        this.token = token;
    }

    /** Standalone entry point — drain for {@code args[0]} seconds (default 180). */
    public static void main(String[] args) throws Exception {
        long seconds = args.length > 0 ? Long.parseLong(args[0]) : 180L;
        OperationsDriver driver = new OperationsDriver(mintOperatorToken());
        AtomicBoolean stop = new AtomicBoolean(false);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        System.out.printf("[operations-driver] draining supply for %ds (poll=%dms)%n", seconds, POLL_MILLIS);
        while (!stop.get() && System.nanoTime() < deadline) {
            int gr = driver.drainGoodsReceipts();
            int ops = driver.drainWorkOrderOperations();
            if (gr > 0 || ops > 0) {
                System.out.printf("[operations-driver] posted %d goods receipt(s), %d operation(s)%n", gr, ops);
            }
            Thread.sleep(POLL_MILLIS);
        }
        System.out.println("[operations-driver] done");
    }

    /** Mint a token for the operator identity (warehouse_clerk + production_planner). */
    public static String mintOperatorToken() {
        return token(OPERATOR_USER, OPERATOR_PASSWORD);
    }

    /**
     * Run the drain loop until {@code stop} is set. Used by {@code OperationsSimulation}
     * as a background thread spanning the injection. Never throws out of the loop — a
     * transient error is logged and retried next poll.
     */
    public void runUntilStopped(AtomicBoolean stop) {
        while (!stop.get()) {
            try {
                drainGoodsReceipts();
                drainWorkOrderOperations();
            } catch (Exception e) {
                System.out.println("[operations-driver] drain error (retrying): " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Post a goods receipt for every PO with an outstanding line. Returns the count posted. */
    public int drainGoodsReceipts() throws Exception {
        int posted = 0;
        for (PurchaseOrder po : openPurchaseOrders()) {
            List<PoLine> lines = outstandingLines(po.id);
            if (lines.isEmpty()) {
                continue;
            }
            StringBuilder lineJson = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                PoLine l = lines.get(i);
                if (i > 0) {
                    lineJson.append(',');
                }
                lineJson.append("""
                    {"purchaseOrderLineId":"%s","productId":"%s","productSku":"%s","productName":"%s",
                     "receivedQuantity":%s,"unitCost":%s}"""
                    .formatted(l.lineId, l.productId, l.productSku, esc(l.productName), l.outstanding.toPlainString(), l.unitPrice.toPlainString()));
            }
            String body = """
                {"goodsReceiptNumber":"GR-%s","purchaseOrderHeaderId":"%s","purchaseOrderNumber":"%s",
                 "supplierId":"%s","supplierName":"%s","warehouseCode":"MAIN","lines":[%s]}"""
                .formatted(System.nanoTime(), po.id, po.number, po.supplierId, esc(po.supplierName), lineJson);
            int code = post(INVENTORY + "/api/goods-receipts", body);
            if (code == 201) {
                posted++;
            } else {
                System.out.printf("[operations-driver] goods-receipt for PO %s -> HTTP %d%n", po.number, code);
            }
        }
        return posted;
    }

    /** Complete the next planned operation on every released/in-progress WO. Returns the count completed. */
    public int drainWorkOrderOperations() throws Exception {
        int completed = 0;
        for (WorkOrderOp op : nextPlannedOperations()) {
            String body = "{\"actualMinutes\":%s}".formatted(op.plannedRunMinutes.toPlainString());
            int code = post(MANUFACTURING + "/api/work-orders/" + op.workOrderId + "/operations/" + op.sequence + "/complete", body);
            if (code == 204 || code == 200) {
                completed++;
            }
            // A 409/400 means ordering / child-gating wasn't satisfied yet — retried next poll.
        }
        return completed;
    }

    // ── JDBC discovery ────────────────────────────────────────────────────

    private record PurchaseOrder(String id, String number, String supplierId, String supplierName) {}
    private record PoLine(String lineId, String productId, String productSku, String productName, BigDecimal outstanding, BigDecimal unitPrice) {}
    private record WorkOrderOp(String workOrderId, int sequence, BigDecimal plannedRunMinutes) {}

    private List<PurchaseOrder> openPurchaseOrders() throws Exception {
        List<PurchaseOrder> out = new ArrayList<>();
        try (Connection c = jdbc(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT purchase_order_header_id, purchase_order_number, supplier_id, supplier_name "
                     + "FROM purchasing.purchase_order_header WHERE status IN ('sent','partially_received')")) {
            while (rs.next()) {
                out.add(new PurchaseOrder(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        }
        return out;
    }

    private List<PoLine> outstandingLines(String purchaseOrderHeaderId) throws Exception {
        List<PoLine> out = new ArrayList<>();
        try (Connection c = jdbc();
             var ps = c.prepareStatement(
                 "SELECT purchase_order_line_id, product_id, product_sku, product_name, "
                     + "ordered_quantity - received_quantity AS outstanding, unit_price "
                     + "FROM purchasing.purchase_order_line "
                     + "WHERE purchase_order_header_id = ? AND received_quantity < ordered_quantity")) {
            ps.setObject(1, java.util.UUID.fromString(purchaseOrderHeaderId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PoLine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)));
                }
            }
        }
        return out;
    }

    /** The lowest-sequence still-planned operation on each released/in-progress WO. */
    private List<WorkOrderOp> nextPlannedOperations() throws Exception {
        List<WorkOrderOp> out = new ArrayList<>();
        try (Connection c = jdbc(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT DISTINCT ON (o.work_order_id) o.work_order_id, o.operation_sequence, o.planned_run_minutes "
                     + "FROM manufacturing.work_order_operation o "
                     + "JOIN manufacturing.work_order w ON w.work_order_id = o.work_order_id "
                     + "WHERE w.status IN ('released','in_progress') AND o.status = 'planned' "
                     + "ORDER BY o.work_order_id, o.operation_sequence")) {
            while (rs.next()) {
                out.add(new WorkOrderOp(rs.getString(1), rs.getInt(2), rs.getBigDecimal(3)));
            }
        }
        return out;
    }

    // ── HTTP + token + JDBC primitives (same idiom as ConcurrentRaceProbesTest) ──

    private int post(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static Connection jdbc() throws Exception {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
}
