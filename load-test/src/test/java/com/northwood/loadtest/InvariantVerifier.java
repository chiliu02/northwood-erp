package com.northwood.loadtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared assertion core of the concurrent load test
 * ({@code docs/concurrent-load-test.md} §6) — plain cross-schema JDBC, reused by
 * the REST execution, the focused race probes, and the demo finale. It does not generate
 * load; it reads the database <em>after</em> the load drains and asserts the
 * conservation / convergence invariants that contention would violate.
 *
 * <p>SQL is grounded in the real baseline schema
 * ({@code config/postgresql/northwood_erp.sql}). It connects as a cross-schema
 * superuser (e.g. {@code postgres}) so it can read every service schema, unlike
 * the per-service {@code <service>_service} roles.
 *
 * <p>Three invariants:
 * <ol>
 *   <li><b>No oversell</b> — no {@code inventory.stock_balance} row is negative.
 *       (The DB CHECKs make a true oversell a constraint violation; this is the
 *       holistic post-run gate that proves none slipped through.)</li>
 *   <li><b>Double-entry</b> — every posted {@code finance.journal_entry_header}
 *       balances (Σ debit = Σ credit across its lines).</li>
 *   <li><b>Convergence</b> — no {@code sales.sales_order_fulfilment_saga} is left
 *       in a non-terminal state. Run only once the load has drained.</li>
 * </ol>
 *
 * <p>Usable two ways: as a Gatling {@code after {}} hook ({@link #assertAll}) and
 * as a standalone CLI ({@link #main}) for the demo finale.
 */
public final class InvariantVerifier {

    private static final String NO_OVERSELL_SQL = """
        SELECT count(*) FROM inventory.stock_balance
        WHERE on_hand_quantity < 0 OR reserved_quantity < 0 OR available_quantity < 0""";

    private static final String UNBALANCED_JOURNALS_SQL = """
        SELECT h.journal_number
        FROM finance.journal_entry_header h
        JOIN finance.journal_entry_line l ON l.journal_entry_header_id = h.journal_entry_header_id
        WHERE h.status = 'posted'
        GROUP BY h.journal_number
        HAVING sum(l.debit_amount) <> sum(l.credit_amount)""";

    private static final String UNCONVERGED_SAGAS_SQL = """
        SELECT count(*) FROM sales.sales_order_fulfilment_saga
        WHERE saga_state NOT IN ('completed', 'rejected', 'compensated', 'failed')""";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public InvariantVerifier(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /** Run every invariant and throw {@link AssertionError} listing all violations, if any. */
    public void assertAll() {
        List<String> violations = check();
        if (!violations.isEmpty()) {
            throw new AssertionError("Load-test invariants violated:\n  - " + String.join("\n  - ", violations));
        }
    }

    /**
     * Convergence is eventual — the final payment → {@code completed} saga
     * transition is itself an async Kafka round-trip, so a single-shot check
     * right after the injection drains can see sagas still mid-flight. Poll
     * convergence (invariant 1, {@code docs/concurrent-load-test.md} §6) until
     * either every fulfilment saga is terminal or the deadline elapses, then run
     * the full conservation check once (no-oversell + double-entry are
     * monotone-stable once sagas are terminal). Throws {@link AssertionError} on
     * any remaining violation, with a census of stuck sagas.
     */
    public void assertAllEventually(long deadlineSeconds) {
        long deadlineNanos = System.nanoTime() + deadlineSeconds * 1_000_000_000L;
        long unconverged = Long.MAX_VALUE;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = conn.createStatement()) {
            while (System.nanoTime() < deadlineNanos) {
                unconverged = scalarCount(statement, UNCONVERGED_SAGAS_SQL);
                if (unconverged == 0) {
                    break;
                }
                Thread.sleep(2_000);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("InvariantVerifier could not query " + jdbcUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("InvariantVerifier convergence wait interrupted", e);
        }
        if (unconverged > 0) {
            throw new AssertionError("Load-test invariants violated:\n  - convergence: " + unconverged
                + " fulfilment saga(s) still non-terminal after " + deadlineSeconds + "s\n  - census: " + stuckSagaCensus());
        }
        assertAll();
    }

    /** Run every invariant and return the list of violation messages (empty = all held). */
    public List<String> check() {
        List<String> violations = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = conn.createStatement()) {

            long oversold = scalarCount(statement, NO_OVERSELL_SQL);
            if (oversold > 0) {
                violations.add("no-oversell: " + oversold + " stock_balance row(s) negative");
            }

            List<String> unbalanced = stringColumn(statement, UNBALANCED_JOURNALS_SQL);
            if (!unbalanced.isEmpty()) {
                violations.add("double-entry: unbalanced journal(s) " + unbalanced);
            }

            long unconverged = scalarCount(statement, UNCONVERGED_SAGAS_SQL);
            if (unconverged > 0) {
                violations.add("convergence: " + unconverged + " fulfilment saga(s) not in a terminal state");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("InvariantVerifier could not query " + jdbcUrl, e);
        }
        return violations;
    }

    /** A {@code state → count} census of the non-terminal fulfilment sagas, for the failure message. */
    private String stuckSagaCensus() {
        String sql = """
            SELECT saga_state, count(*) FROM sales.sales_order_fulfilment_saga
            WHERE saga_state NOT IN ('completed', 'rejected', 'compensated', 'failed')
            GROUP BY saga_state ORDER BY count(*) DESC""";
        List<String> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "=" + rs.getLong(2));
            }
        } catch (SQLException e) {
            return "<census query failed: " + e.getMessage() + ">";
        }
        return rows.isEmpty() ? "<none>" : String.join(", ", rows);
    }

    private static long scalarCount(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static List<String> stringColumn(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /**
     * Standalone runner for the demo finale. Reads connection settings from system
     * properties, defaulting to the local docker-compose Postgres:
     * {@code -Djdbc.url=... -Djdbc.user=postgres -Djdbc.password=postgres}.
     */
    public static void main(String[] args) {
        String url = System.getProperty("jdbc.url", "jdbc:postgresql://localhost:5432/northwood_erp");
        String user = System.getProperty("jdbc.user", "postgres");
        String password = System.getProperty("jdbc.password", "postgres");
        List<String> violations = new InvariantVerifier(url, user, password).check();
        if (violations.isEmpty()) {
            System.out.println("✓ All load-test invariants hold (no oversell, ledger balances, sagas converged).");
        } else {
            System.out.println("✗ Invariants violated:");
            violations.forEach(v -> System.out.println("  - " + v));
            System.exit(1);
        }
    }
}
