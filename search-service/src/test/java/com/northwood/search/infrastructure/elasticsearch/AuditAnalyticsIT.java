package com.northwood.search.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.search.application.AuditEventProjection;
import com.northwood.search.application.AuditSearchQueryPort;
import com.northwood.search.application.dto.AuditSearchResults;
import com.northwood.search.application.dto.FacetBucket;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Elasticsearch test for the audit/event-analytics read model (read model
 * C): every event is appended to {@code audit-events}, search filters by service,
 * re-appending the same event id stays idempotent, and the {@code sourceService}
 * facet counts events per producer (the "events per service" aggregation).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditAnalyticsIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
        new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.0.4"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    @Autowired
    private AuditEventProjection auditEvents;

    @Autowired
    private AuditSearchQueryPort auditSearch;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    @BeforeEach
    void clearIndex() {
        elasticsearch.indexOps(AuditEventDocument.class).delete();
        elasticsearch.indexOps(AuditEventDocument.class).createWithMapping();
    }

    @Test
    void appends_events_and_aggregates_per_service() {
        auditEvents.append(event("sales", "sales.SalesOrderPlaced"));
        auditEvents.append(event("sales", "sales.SalesOrderShipped"));
        auditEvents.append(event("product", "product.ProductCreated"));
        refresh();

        AuditSearchResults all = auditSearch.search(null, null, null, 0, 20);
        assertThat(all.totalHits()).isEqualTo(3);
        assertThat(all.serviceFacets()).filteredOn(b -> b.key().equals("sales"))
            .first().extracting(FacetBucket::count).isEqualTo(2L);

        AuditSearchResults onlySales = auditSearch.search(null, "sales", null, 0, 20);
        assertThat(onlySales.totalHits()).isEqualTo(2);
    }

    @Test
    void re_appending_the_same_event_is_idempotent() {
        EventEnvelope e = event("finance", "finance.CustomerInvoiceCreated");
        auditEvents.append(e);
        auditEvents.append(e);
        refresh();

        assertThat(auditSearch.search(null, null, null, 0, 20).totalHits()).isEqualTo(1);
    }

    private static EventEnvelope event(String sourceService, String eventType) {
        return new EventEnvelope(
            UUID.randomUUID(), "Order", UUID.randomUUID(), eventType, 1,
            "{\"sample\":\"payload\"}",
            Map.of(EventEnvelope.HEADER_SOURCE_SERVICE, sourceService),
            UUID.randomUUID(), null, "alice", Instant.now());
    }

    private void refresh() {
        elasticsearch.indexOps(AuditEventDocument.class).refresh();
    }
}
