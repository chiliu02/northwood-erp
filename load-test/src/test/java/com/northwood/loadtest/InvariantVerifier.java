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
 * every execution (REST / Web-UI) and by the demo finale. It does not generate
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
