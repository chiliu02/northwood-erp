package com.northwood.loadtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;

/**
 * The shared assertion core of the concurrent load test
 * ({@code docs/concurrent-load-test.md} §6) — reused by the REST execution, the
 * focused race probes, and the demo finale. It does not generate load; it reads
 * the database (and the broker) <em>after</em> the load drains and asserts the
 * conservation / convergence invariants that contention would violate.
 *
 * <p>SQL is grounded in the real baseline schema
 * ({@code config/postgresql/northwood_erp.sql}). It connects as a cross-schema
 * superuser (e.g. {@code postgres}) so it can read every service schema, unlike
 * the per-service {@code <service>_service} roles.
 *
 * <p>Six invariants (the full §6 set):
 * <ol>
 *   <li><b>Convergence</b> — no {@code sales.sales_order_fulfilment_saga} is left
 *       in a non-terminal state. Polled to a deadline by
 *       {@link #assertAllEventually(long)} (payment → completed is itself async).</li>
 *   <li><b>No oversell</b> — no {@code inventory.stock_balance} row is negative.
 *       (The DB CHECKs make a true oversell a constraint violation; this is the
 *       holistic post-run gate that proves none slipped through.)</li>
 *   <li><b>Double-entry</b> — every posted {@code finance.journal_entry_header}
 *       balances (Σ debit = Σ credit across its lines).</li>
 *   <li><b>Idempotency (exactly-once effect)</b> — no inbox event is applied
 *       twice. Each {@code <schema>.inbox_message} holds at most one row per
 *       {@code (message_id, handler_name)}. The table's UNIQUE is
 *       {@code (message_id, handler_name, processed_at)} — partitioning forces
 *       {@code processed_at} into it, so the constraint does NOT prevent a
 *       duplicate {@code (message_id, handler_name)} (the rebalance-window TOCTOU
 *       in {@code docs/messaging.md}); the advisory-lock dedup gate is what closes
 *       it. A duplicate row here means the gate let a redelivery through twice —
 *       a double effect. Plus the quantity-effect gate: no
 *       {@code sales.sales_order_line} is over-shipped / over-reserved.</li>
 *   <li><b>Per-aggregate ordering / state consistency</b> — events for one SO
 *       apply in saga-legal order (partition key = {@code aggregateId}). Asserted
 *       via the forbidden end-state combinations an out-of-order / lost apply
 *       would leave: an order both shipped and cancelled, or a {@code completed}
 *       saga whose lines are not fully shipped.</li>
 *   <li><b>Empty DLT</b> — no {@code *.events.dlt} / {@code *.events.dlt.parked}
 *       topic holds a record (no saga wedged on a {@code CHECK} violation). This
 *       one invariant lives in Kafka, checked via the AdminClient; skipped only
 *       when no {@code kafka.bootstrap} is reachable (logged).</li>
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

    /** Every service schema that owns an {@code inbox_message} table. */
    private static final List<String> INBOX_SCHEMAS =
        List.of("product", "sales", "inventory", "manufacturing", "purchasing", "finance", "reporting");

    /** Idempotency: a {@code (message_id, handler_name)} that landed more than once = a double-applied redelivery. */
    private static final String DUPLICATE_INBOX_SQL_TEMPLATE = """
        SELECT count(*) FROM (
            SELECT message_id, handler_name FROM %s.inbox_message
            GROUP BY message_id, handler_name HAVING count(*) > 1
        ) duplicated""";

    /** Idempotency (quantity effect): a line whose cumulative shipped/reserved exceeds what was ordered. */
    private static final String OVER_APPLIED_LINE_SQL = """
        SELECT count(*) FROM sales.sales_order_line
        WHERE shipped_quantity > ordered_quantity OR reserved_quantity > ordered_quantity""";

    /** Ordering: an order can never be both cancelled and have shipped goods (the cancel-vs-ship hazard). */
    private static final String SHIPPED_AND_CANCELLED_SQL = """
        SELECT count(*) FROM sales.sales_order_header h
        WHERE h.status = 'cancelled'
          AND EXISTS (
            SELECT 1 FROM sales.sales_order_line l
            WHERE l.sales_order_header_id = h.sales_order_header_id AND l.shipped_quantity > 0)""";

    /** Ordering: a completed fulfilment saga whose order is not fully shipped (lost/out-of-order ship event). */
    private static final String COMPLETED_BUT_UNSHIPPED_SQL = """
        SELECT count(*) FROM sales.sales_order_fulfilment_saga s
        JOIN sales.sales_order_line l ON l.sales_order_header_id = s.sales_order_header_id
        WHERE s.saga_state = 'completed' AND l.shipped_quantity < l.ordered_quantity""";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String kafkaBootstrap;

    /**
     * @param kafkaBootstrap bootstrap servers for the empty-DLT check; {@code null}
     *     or blank skips invariant 6 (logged). The 3-arg overload reads it from the
     *     {@code kafka.bootstrap} system property (default {@code localhost:9092}).
     */
    public InvariantVerifier(String jdbcUrl, String username, String password, String kafkaBootstrap) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.kafkaBootstrap = kafkaBootstrap;
    }

    public InvariantVerifier(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, System.getProperty("kafka.bootstrap", "localhost:9092"));
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
     * the full conservation check once (the remaining invariants are
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

            // 2 — no oversell
            long oversold = scalarCount(statement, NO_OVERSELL_SQL);
            if (oversold > 0) {
                violations.add("no-oversell: " + oversold + " stock_balance row(s) negative");
            }

            // 3 — double-entry balance
            List<String> unbalanced = stringColumn(statement, UNBALANCED_JOURNALS_SQL);
            if (!unbalanced.isEmpty()) {
                violations.add("double-entry: unbalanced journal(s) " + unbalanced);
            }

            // 1 — convergence (single-shot; assertAllEventually polls it first)
            long unconverged = scalarCount(statement, UNCONVERGED_SAGAS_SQL);
            if (unconverged > 0) {
                violations.add("convergence: " + unconverged + " fulfilment saga(s) not in a terminal state");
            }

            // 4 — idempotency: no inbox event applied twice (per schema), no over-applied quantity
            List<String> dupInboxSchemas = new ArrayList<>();
            for (String schema : INBOX_SCHEMAS) {
                if (scalarCount(statement, DUPLICATE_INBOX_SQL_TEMPLATE.formatted(schema)) > 0) {
                    dupInboxSchemas.add(schema);
                }
            }
            if (!dupInboxSchemas.isEmpty()) {
                violations.add("idempotency: duplicate inbox application in schema(s) " + dupInboxSchemas);
            }
            long overApplied = scalarCount(statement, OVER_APPLIED_LINE_SQL);
            if (overApplied > 0) {
                violations.add("idempotency: " + overApplied + " sales_order_line(s) over-shipped/over-reserved");
            }

            // 5 — per-aggregate ordering / state consistency
            long shippedAndCancelled = scalarCount(statement, SHIPPED_AND_CANCELLED_SQL);
            if (shippedAndCancelled > 0) {
                violations.add("ordering: " + shippedAndCancelled + " order(s) both shipped and cancelled");
            }
            long completedUnshipped = scalarCount(statement, COMPLETED_BUT_UNSHIPPED_SQL);
            if (completedUnshipped > 0) {
                violations.add("ordering: " + completedUnshipped + " completed saga(s) with an unshipped line");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("InvariantVerifier could not query " + jdbcUrl, e);
        }

        // 6 — empty DLT (Kafka, not JDBC)
        checkEmptyDlt().ifPresent(violations::add);

        return violations;
    }

    /**
     * Invariant 6 — every dead-letter topic ({@code *.dlt} and the redrive terminal
     * {@code *.dlt.parked}) is empty. A non-empty DLT means a saga wedged on a poison
     * record (the {@code 23514} CHECK → DLT-loop failure mode of
     * {@code docs/validations.md}). Returns a violation string, or empty if all DLT
     * topics are empty / there is no DLT topic / no broker is configured.
     */
    private java.util.Optional<String> checkEmptyDlt() {
        if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
            System.out.println("[InvariantVerifier] empty-DLT check skipped (no kafka.bootstrap configured)");
            return java.util.Optional.empty();
        }
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        try (Admin admin = Admin.create(props)) {
            List<String> dltTopics = admin.listTopics().names().get().stream()
                .filter(t -> t.endsWith(".dlt") || t.endsWith(".dlt.parked"))
                .sorted()
                .toList();
            if (dltTopics.isEmpty()) {
                return java.util.Optional.empty();
            }
            // For every partition of every DLT topic, compare earliest vs latest offset.
            // latest > earliest ⇒ the topic holds at least one undrained record.
            List<TopicPartition> partitions = new ArrayList<>();
            Map<String, TopicDescription> described = admin.describeTopics(dltTopics).allTopicNames().get();
            for (TopicDescription td : described.values()) {
                td.partitions().forEach(p -> partitions.add(new TopicPartition(td.name(), p.partition())));
            }
            Map<TopicPartition, OffsetSpec> earliestSpec = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));
            Map<TopicPartition, OffsetSpec> latestSpec = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResultInfo> earliest = admin.listOffsets(earliestSpec).all().get();
            Map<TopicPartition, ListOffsetsResultInfo> latest = admin.listOffsets(latestSpec).all().get();

            List<String> nonEmpty = new ArrayList<>();
            for (TopicPartition tp : partitions) {
                long records = latest.get(tp).offset() - earliest.get(tp).offset();
                if (records > 0) {
                    nonEmpty.add(tp.topic() + "-" + tp.partition() + "=" + records);
                }
            }
            return nonEmpty.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of("empty-DLT: dead-letter record(s) present " + nonEmpty);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("InvariantVerifier DLT check interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("InvariantVerifier could not reach Kafka at " + kafkaBootstrap
                + " for the empty-DLT check", e);
        }
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
     * properties, defaulting to the local docker-compose Postgres + Kafka:
     * {@code -Djdbc.url=... -Djdbc.user=postgres -Djdbc.password=postgres
     * -Dkafka.bootstrap=localhost:9092}.
     */
    public static void main(String[] args) {
        String url = System.getProperty("jdbc.url", "jdbc:postgresql://localhost:5432/northwood_erp");
        String user = System.getProperty("jdbc.user", "postgres");
        String password = System.getProperty("jdbc.password", "postgres");
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        List<String> violations = new InvariantVerifier(url, user, password, bootstrap).check();
        if (violations.isEmpty()) {
            System.out.println("✓ All load-test invariants hold "
                + "(convergence, no oversell, ledger balances, idempotency, ordering, empty DLT).");
        } else {
            System.out.println("✗ Invariants violated:");
            violations.forEach(v -> System.out.println("  - " + v));
            System.exit(1);
        }
    }
}
