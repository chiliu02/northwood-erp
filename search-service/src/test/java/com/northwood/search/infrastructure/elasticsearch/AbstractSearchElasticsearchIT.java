package com.northwood.search.infrastructure.elasticsearch;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for the search-service Elasticsearch integration tests: one
 * Testcontainers Elasticsearch (reused across the IT classes in a fork) plus the
 * context configuration that lets the full {@code SearchApplication} boot in a
 * test.
 *
 * <p>{@code webEnvironment = NONE} keeps this a non-web context, so the
 * {@code kafka}-profile consumer stays inactive and the tests drive the
 * projection + query-port beans directly (the search-side analogue of
 * reporting's {@code Jdbc*ProjectionIT}s). That non-web choice means two
 * auto-config families must be switched off for the context to load:
 * <ul>
 *   <li><b>the JDBC/DataSource auto-configs</b> — search-service owns no
 *       database, but depends on {@code shared} which brings them; with no
 *       datasource they fail at boot. (Same set as {@code application.yml}'s
 *       exclude list.)</li>
 *   <li><b>the resource-server security config</b> — its {@code SecurityFilterChain}
 *       needs an {@code HttpSecurity} bean that only exists in a servlet web
 *       context; irrelevant to testing the ES read models anyway.</li>
 * </ul>
 * Excluded here via properties (not {@code application.yml}) so the security
 * config stays active in the real, servlet-based service.
 *
 * <p>The Elasticsearch container is a <b>singleton</b> started once in a static
 * block and never explicitly stopped (Ryuk reaps it at JVM exit). This shares
 * one container across all three IT classes — the {@code @Testcontainers} +
 * {@code @Container} managed lifecycle would instead stop the static container
 * after the first class, leaving the next class connecting to a dead port.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration,"
            + "com.northwood.shared.infrastructure.db.LiquibaseConfig,"
            + "com.northwood.shared.infrastructure.inbox.jdbc.JdbcInboxAutoConfiguration,"
            + "com.northwood.shared.infrastructure.outbox.jdbc.JdbcOutboxAutoConfiguration,"
            + "com.northwood.shared.infrastructure.outbox.jdbc.OutboxMetricsAutoConfiguration,"
            + "com.northwood.shared.infrastructure.saga.SagaInvariantsAutoConfiguration,"
            + "com.northwood.shared.infrastructure.security.OAuth2ResourceServerSecurityConfig",
        "northwood.audit.enabled=false"
    }
)
abstract class AbstractSearchElasticsearchIT {

    static final ElasticsearchContainer ELASTICSEARCH =
        new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.0.4"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    static {
        ELASTICSEARCH.start();
    }

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }
}
