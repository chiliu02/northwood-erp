package com.northwood.shared.infrastructure.inbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.shared.application.inbox.InboxRow;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.25 Tier 1: real-Postgres test for {@link JdbcInboxAdapter}, the shared
 * idempotency-dedup adapter reused by all 7 services. Covers what a mocked
 * unit test cannot:
 *
 * <ul>
 *   <li>{@code alreadyProcessed} against a real {@code COUNT(*)} — false for an
 *       unseen message, true once {@code recordProcessed} has landed the row;</li>
 *   <li>dedup is keyed on {@code (message_id, consumer_name)} — the same message
 *       seen by a different consumer is still "unprocessed" for that consumer
 *       (the property that lets every service consume the same event);</li>
 *   <li>{@code recordProcessed} persists all columns and casts {@code payload}
 *       via {@code ?::jsonb}.</li>
 * </ul>
 *
 * <p>Runs against the {@code product} schema; the adapter is service-agnostic
 * (unqualified {@code inbox_message} resolves via {@code search_path}).
 */
class JdbcInboxAdapterIT {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("northwood_erp")
            .withUsername("postgres")
            .withPassword("postgres");

    private static HikariDataSource DATA_SOURCE;
    private static JdbcTemplate JDBC;
    private static JdbcInboxAdapter ADAPTER;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void bootContainerAndSchema() {
        Startables.deepStart(POSTGRES).join();
        applySqlFile(Path.of("..", "db", "northwood_erp.sql"));
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setConnectionInitSql("SET search_path = product, shared");
        JDBC = new JdbcTemplate(DATA_SOURCE);
        ADAPTER = new JdbcInboxAdapter(JDBC);
    }

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

    @BeforeEach
    void clearInbox() {
        JDBC.execute("TRUNCATE product.inbox_message");
    }

    @Test
    void alreadyProcessed_is_false_for_an_unseen_message() {
        assertThat(ADAPTER.alreadyProcessed(UUID.randomUUID(), "product.SomeHandler")).isFalse();
    }

    @Test
    void recordProcessed_then_alreadyProcessed_is_true() {
        UUID messageId = UUID.randomUUID();
        String consumer = "product.ProductMaterialsCostComputedHandler";

        ADAPTER.recordProcessed(InboxRow.processed(
            UUID.randomUUID(), messageId, consumer,
            "manufacturing.ProductMaterialsCostComputed", 1, 7L, "{}"
        ));

        assertThat(ADAPTER.alreadyProcessed(messageId, consumer)).isTrue();
    }

    @Test
    void dedup_is_keyed_per_consumer() {
        UUID messageId = UUID.randomUUID();
        ADAPTER.recordProcessed(InboxRow.processed(
            UUID.randomUUID(), messageId, "consumer.A",
            "product.SomeEvent", 1, null, "{}"
        ));

        assertThat(ADAPTER.alreadyProcessed(messageId, "consumer.A")).isTrue();
        assertThat(ADAPTER.alreadyProcessed(messageId, "consumer.B")).isFalse();
    }

    @Test
    void recordProcessed_persists_all_columns_and_casts_payload_jsonb() {
        UUID messageId = UUID.randomUUID();
        String consumer = "product.SomeHandler";
        String payload = "{\"productId\":\"abc\",\"cost\":42.5}";

        ADAPTER.recordProcessed(InboxRow.processed(
            UUID.randomUUID(), messageId, consumer,
            "manufacturing.ProductMaterialsCostComputed", 2, 99L, payload
        ));

        Map<String, Object> row = JDBC.queryForMap(
            """
            SELECT event_type, event_version, source_sequence_number,
                   payload::text AS payload_text, status, processed_at
            FROM product.inbox_message
            WHERE message_id = ? AND consumer_name = ?
            """,
            messageId, consumer
        );

        assertThat(row.get("event_type")).isEqualTo("manufacturing.ProductMaterialsCostComputed");
        assertThat(row.get("event_version")).isEqualTo(2);
        assertThat(((Number) row.get("source_sequence_number")).longValue()).isEqualTo(99L);
        assertThat(row.get("status")).isEqualTo("processed");
        assertThat(row.get("processed_at")).isNotNull();
        assertThat(JSON.readTree((String) row.get("payload_text"))).isEqualTo(JSON.readTree(payload));
    }
}
