package com.northwood.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.application.inbox.StockReservedHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * The full delivery-path proof that the sales-order-fulfilment saga
 * advances <em>exactly once</em> under a genuine cross-partition duplicate
 * ({@code docs/messaging.md} → <em>Hazards when scaling past 1 partition</em>,
 * item 2). The DB-level twin
 * ({@code JdbcSalesOrderFulfilmentSagaAdapterIT.claimDue_under_concurrency_...})
 * proves the {@code FOR UPDATE SKIP LOCKED} primitive in isolation; the
 * single-partition twin ({@code inventory.DuplicateDeliveryAppliedOnceIT}) keys
 * both copies to one partition so they process strictly in order on one thread —
 * the gate "wins" without ever contending. Neither exercises the case this test
 * does: <b>two copies of one event on two partitions, consumed concurrently by
 * two listener threads</b>, where the only thing standing between them and a
 * double-advance is the advisory-lock dedup gate
 * ({@code AbstractInboxHandler.handle} → {@code AdvisoryLockInboxDedupStrategy})
 * running under real contention.
 *
 * <p><b>Shape.</b> A real multi-partition broker ({@code inventory.events}, 2
 * partitions) + the full sales app booted with
 * {@code northwood.kafka.listener.concurrency=2} (one thread per partition — the
 * exact production knob added in {@code KafkaMessagingAutoConfiguration}). One
 * {@code inventory.StockReserved} (full reservation, {@code eventId E}) is
 * published <em>verbatim to both partitions</em> — an explicit-partition publish
 * bypasses the {@code aggregateId} key hashing that would otherwise co-locate
 * the two copies. The two listener threads pick the copies up simultaneously and
 * race the gate.
 *
 * <p><b>Determinism.</b> Asserting "exactly once" needs both copies to have
 * fully resolved (including the loser's gated skip) before we count. After
 * {@code E} on each partition we publish a <em>marker</em> {@code StockReserved}
 * for a <em>distinct</em> sales order ({@code SO_A} on partition 0,
 * {@code SO_B} on partition 1). A partition is consumed in strict offset order
 * by its single thread, so once both markers' sagas reach {@code ready_to_ship},
 * both threads have processed {@code E} to completion — the loser only proceeds
 * past its blocked {@code pg_advisory_xact_lock} after the winner commits. No
 * sleeps, no "assert-stable" polling.
 *
 * <p><b>The three exactly-once signals</b> (a double-advance would break each):
 * <ol>
 *   <li>one {@code inbox_message} row for {@code (E, sales.fulfilment-saga)} —
 *       the gate skipped the duplicate (the partitioned unique constraint does
 *       <em>not</em> enforce this; see {@code DuplicateDeliveryAppliedOnceIT});</li>
 *   <li>one {@code sales.SalesOrderReadyToShip} outbox row for {@code SO} — the
 *       downstream command was emitted once, not twice;</li>
 *   <li>the {@code SO} saga sits at {@code ready_to_ship}.</li>
 * </ol>
 *
 * <p>Container + baseline-schema shape mirrors the other sales ITs
 * ({@code JdbcSalesOrderRepositoryIT}): the full {@code northwood_erp.sql}
 * baseline is applied so the whole sales context boots, then the three orders +
 * sagas are seeded in {@code stock_reservation_requested} — a state the worker's
 * {@code activeStates()} excludes, so the {@code @Scheduled} worker never races
 * the inbox-driven transition under test.
 */
@SpringBootTest
@ActiveProfiles("kafka")
class SalesFulfilmentSagaCrossPartitionRaceIT {

    private static final String TOPIC = "inventory.events";
    private static final int PARTITIONS = 2;

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
        applySqlFile(Path.of("..", "config", "postgresql", "northwood_erp.sql"));
        createTopic(TOPIC, PARTITIONS);
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Narrow the consumer to just the topic under test (the profile default
        // also lists product/manufacturing/finance — irrelevant here).
        r.add("northwood.kafka.subscribe-topics", () -> TOPIC);
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        // One listener thread per partition — the cross-partition race needs
        // genuine parallelism. Exercises the configurable-concurrency clamp:
        // concurrency(2) == partitions(2), no idle threads.
        r.add("northwood.kafka.topic.partitions", () -> PARTITIONS);
        r.add("northwood.kafka.listener.concurrency", () -> PARTITIONS);
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void duplicateStockReserved_onTwoPartitions_advancesSagaExactlyOnce() {
        UUID racedOrder = seedOrderAwaitingReservation("SO-RACE");
        UUID markerOrderP0 = seedOrderAwaitingReservation("SO-MARK-P0");
        UUID markerOrderP1 = seedOrderAwaitingReservation("SO-MARK-P1");

        UUID racedEventId = UUID.randomUUID();
        // The duplicate: one event, published verbatim to BOTH partitions so two
        // listener threads consume it at once. Explicit partition overrides the
        // aggregateId key hash that would otherwise pin both copies to one.
        String racedEnvelope = fullReservationEnvelope(racedEventId, racedOrder);
        publishToPartition(0, racedEnvelope);
        publishToPartition(1, racedEnvelope);

        // Per-partition markers (distinct orders → no dedup interaction). Landing
        // after E on the same partition, in offset order, they prove that
        // partition's thread finished E.
        publishToPartition(0, fullReservationEnvelope(UUID.randomUUID(), markerOrderP0));
        publishToPartition(1, fullReservationEnvelope(UUID.randomUUID(), markerOrderP1));

        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            assertThat(sagaState(markerOrderP0)).isEqualTo("supply_secured");
            assertThat(sagaState(markerOrderP1)).isEqualTo("supply_secured");
        });

        // Both partitions have drained past E → the race is fully resolved.
        Integer inboxRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class, racedEventId, StockReservedHandler.CONSUMER_NAME);
        assertThat(inboxRows)
            .withFailMessage("the cross-partition duplicate must be recorded exactly once "
                + "(the advisory-lock gate skips the concurrent copy)")
            .isEqualTo(1);

        Integer readyToShipRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.outbox_message "
                + "WHERE aggregate_id = ? AND event_type = 'sales.SalesOrderReadyToShip'",
            Integer.class, racedOrder);
        assertThat(readyToShipRows)
            .withFailMessage("SalesOrderReadyToShip must be emitted exactly once, not once per partition copy")
            .isEqualTo(1);

        assertThat(sagaState(racedOrder)).isEqualTo("supply_secured");
    }

    // ------------------------------------------------------------------
    // Event construction
    // ------------------------------------------------------------------

    /**
     * A full-reservation {@code inventory.StockReserved} envelope for {@code SO}:
     * line 1 fully reserved (no shortage), driving the saga
     * {@code stock_reservation_requested → ready_to_ship}.
     */
    private String fullReservationEnvelope(UUID eventId, UUID salesOrderId) {
        UUID reservationId = UUID.randomUUID();
        StockReserved payload = new StockReserved(
            eventId, reservationId, salesOrderId, reservationId, StockReserved.STATUS_RESERVED,
            List.of(new StockReserved.ReservedLine(
                1, UUID.randomUUID(),
                new BigDecimal("3"), new BigDecimal("3"), BigDecimal.ZERO,
                StockReserved.STATUS_RESERVED)),
            Instant.now());
        EventEnvelope envelope = new EventEnvelope(
            eventId, InventoryAggregateTypes.STOCK_RESERVATION, reservationId,
            StockReserved.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            Map.of(EventEnvelope.HEADER_SOURCE_SERVICE, "inventory"),
            null, null, null, Instant.now());
        return json.writeValueAsString(envelope);
    }

    /**
     * Blocks until the broker acks, so each partition's log is built in call
     * order ({@code [E, marker]}). The cross-partition race is on the consumer
     * side (two listener threads), not here — this only fixes per-partition
     * ordering.
     */
    private void publishToPartition(int partition, String value) {
        kafkaTemplate.send(TOPIC, partition, UUID.randomUUID().toString(), value).join();
    }

    // ------------------------------------------------------------------
    // Seed
    // ------------------------------------------------------------------

    /**
     * Seeds a customer (once), a single-line on-shipment order, and its
     * fulfilment saga parked at {@code stock_reservation_requested} — the
     * inbox-driven state {@code StockReserved} resolves. Returns the
     * {@code sales_order_header_id}.
     */
    private UUID seedOrderAwaitingReservation(String orderNumber) {
        jdbc.update("INSERT INTO sales.customer (customer_id, customer_code, name) "
            + "VALUES (?, 'CUST-RACE', 'Race IT') ON CONFLICT (customer_id) DO NOTHING", CUSTOMER_ID);

        UUID orderId = UUID.randomUUID();
        // version=1, not 0: JdbcSalesOrderRepository.save() treats version==0 as a
        // brand-new aggregate and takes the INSERT path. A placed order whose
        // fulfilment saga is already running is version>=1, so seed it that way —
        // otherwise the handler's recordReservation→save re-INSERTs the header and
        // dies on a duplicate-key (rolling back the saga transition under test).
        jdbc.update("INSERT INTO sales.sales_order_header "
            + "(sales_order_header_id, order_number, customer_id, customer_code, customer_name, "
            + " status, payment_terms, total_amount, version) "
            + "VALUES (?, ?, ?, 'CUST-RACE', 'Race IT', 'open', 'on_shipment', 150.00, 1)",
            orderId, orderNumber, CUSTOMER_ID);
        jdbc.update("INSERT INTO sales.sales_order_line "
            + "(sales_order_line_id, sales_order_header_id, line_number, product_id, product_sku, "
            + " product_name, ordered_quantity, unit_price, line_total) "
            + "VALUES (?, ?, 1, ?, 'FG-RACE-1', 'Race Finished Good', 3, 50.000000, 150.00)",
            UUID.randomUUID(), orderId, UUID.randomUUID());
        jdbc.update("INSERT INTO sales.sales_order_fulfilment_saga "
            + "(saga_id, sales_order_header_id, saga_state, current_step, data, version) "
            + "VALUES (?, ?, 'stock_reservation_requested', 'wait_for_stock_reservation', '{}'::jsonb, 0)",
            UUID.randomUUID(), orderId);
        return orderId;
    }

    private String sagaState(UUID salesOrderHeaderId) {
        return jdbc.queryForObject(
            "SELECT saga_state FROM sales.sales_order_fulfilment_saga WHERE sales_order_header_id = ?",
            String.class, salesOrderHeaderId);
    }

    // ------------------------------------------------------------------
    // Container bootstrap
    // ------------------------------------------------------------------

    private static void applySqlFile(Path file) {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath(), e);
        }
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply " + file.getFileName(), e);
        }
    }

    /**
     * Pre-declares {@code inventory.events} with {@value PARTITIONS} partitions
     * <em>before</em> the sales context boots, so the consumer never triggers a
     * 1-partition broker auto-create (which would defeat the cross-partition
     * race). The sales app declares only its own {@code sales.events}, not this
     * upstream topic.
     */
    private static void createTopic(String topic, int partitions) {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create topic " + topic, e);
        }
    }
}
