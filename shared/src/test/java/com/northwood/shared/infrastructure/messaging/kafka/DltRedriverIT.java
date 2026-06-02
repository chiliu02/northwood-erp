package com.northwood.shared.infrastructure.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-broker tests for the {@link DltRedriver}. Pin the three
 * behaviours the per-service auto-redrive contract rests on:
 *
 * <ul>
 *   <li><b>own record is re-applied</b> — a DLT record whose
 *       {@code kafka_dlt-original-consumer-group} header matches this service is
 *       re-dispatched through the live {@link KafkaInboxDispatcher} fan-out; a
 *       handler that now succeeds clears it (no park);</li>
 *   <li><b>another service's record is skipped</b> — a DLT record stamped with a
 *       <em>different</em> origin group is committed without re-applying (the
 *       header filter — that record belongs to another service's redriver);</li>
 *   <li><b>unrecoverable record is parked</b> — a record that keeps failing is
 *       re-applied up to {@code max-attempts}, then published to the terminal
 *       {@code <topic>.dlt.parked} store, carrying the origin-group + attempt
 *       headers for ops.</li>
 * </ul>
 *
 * <p>The container is hand-wired (a raw {@link MessageListener} bound to
 * {@link DltRedriver#onDltMessage}) so the test needs only a broker — no Spring
 * context — exactly like {@code KafkaInboxDispatcherDeliveryIT}. The redriver's
 * own consumer group is set on the container; the group it <em>filters</em> on
 * is the {@code ownGroup} constructor arg, so the two are independent here.
 */
class DltRedriverIT {

    private static final String EVENT_TYPE = "test.RedriveProbe";
    private static final String OWN_GROUP = "test-service";

    static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    static {
        Startables.deepStart(KAFKA).join();
    }

    private final ObjectMapper json = new ObjectMapper();

    /**
     * A DLT record tagged with this service's origin group is re-applied via the
     * dispatcher. The handler fails the first re-apply then succeeds on the
     * retry, proving the redrive loop retries within one invocation and clears
     * the record without parking it.
     */
    @Test
    void own_record_is_redriven_through_the_dispatcher_until_it_succeeds() throws Exception {
        String dltTopic = "shared.redrive-it.own.dlt";
        String parkedTopic = dltTopic + DltRedriver.PARKED_SUFFIX;
        String groupId = "redrive-own-" + UUID.randomUUID();
        createTopic(dltTopic);
        createTopic(parkedTopic);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        InboxEnvelopeHandler handler = new InboxEnvelopeHandler() {
            @Override public boolean handles(String eventType) { return EVENT_TYPE.equals(eventType); }
            @Override public String consumerName() { return "test.RedriveProbeHandler"; }
            @Override public void handle(EventEnvelope envelope) {
                if (attempts.incrementAndGet() == 1) {
                    throw new RuntimeException("transient on first redrive attempt");
                }
                succeeded.countDown();
            }
        };
        var redriver = redriver(handler, /*maxAttempts*/ 3, /*delayMs*/ 50);

        var container = startContainer(groupId, dltTopic, redriver);
        try (KafkaConsumer<String, String> parkedConsumer = consumer("parked-" + UUID.randomUUID())) {
            parkedConsumer.subscribe(List.of(parkedTopic));
            publishDltRecord(dltTopic, json.writeValueAsString(probe()), OWN_GROUP);

            assertThat(succeeded.await(30, TimeUnit.SECONDS))
                .withFailMessage("redrive never re-applied the record successfully").isTrue();
            assertThat(attempts.get()).isEqualTo(2);
            awaitCommittedOffset(groupId, dltTopic, 1L);
            assertNothingFor(parkedConsumer, Duration.ofSeconds(2));
        } finally {
            container.stop();
        }
    }

    /**
     * A DLT record stamped with a <em>different</em> origin group is committed
     * without ever reaching a handler (the header filter) and is never parked.
     */
    @Test
    void another_services_record_is_skipped() throws Exception {
        String dltTopic = "shared.redrive-it.skip.dlt";
        String parkedTopic = dltTopic + DltRedriver.PARKED_SUFFIX;
        String groupId = "redrive-skip-" + UUID.randomUUID();
        createTopic(dltTopic);
        createTopic(parkedTopic);

        AtomicInteger attempts = new AtomicInteger();
        InboxEnvelopeHandler handler = new InboxEnvelopeHandler() {
            @Override public boolean handles(String eventType) { return EVENT_TYPE.equals(eventType); }
            @Override public String consumerName() { return "test.RedriveProbeHandler"; }
            @Override public void handle(EventEnvelope envelope) { attempts.incrementAndGet(); }
        };
        var redriver = redriver(handler, 3, 50);

        var container = startContainer(groupId, dltTopic, redriver);
        try (KafkaConsumer<String, String> parkedConsumer = consumer("parked-" + UUID.randomUUID())) {
            parkedConsumer.subscribe(List.of(parkedTopic));
            publishDltRecord(dltTopic, json.writeValueAsString(probe()), "some-other-service");

            // Offset advances → the record was consumed and skipped.
            awaitCommittedOffset(groupId, dltTopic, 1L);
            assertThat(attempts.get())
                .withFailMessage("another service's record must not be re-applied here").isZero();
            assertNothingFor(parkedConsumer, Duration.ofSeconds(2));
        } finally {
            container.stop();
        }
    }

    /**
     * A record this service owns that keeps failing is retried up to
     * {@code max-attempts}, then published to {@code <topic>.dlt.parked} with the
     * origin-group and attempt headers preserved.
     */
    @Test
    void unrecoverable_own_record_is_parked_after_max_attempts() throws Exception {
        String dltTopic = "shared.redrive-it.park.dlt";
        String parkedTopic = dltTopic + DltRedriver.PARKED_SUFFIX;
        String groupId = "redrive-park-" + UUID.randomUUID();
        createTopic(dltTopic);
        createTopic(parkedTopic);

        AtomicInteger attempts = new AtomicInteger();
        InboxEnvelopeHandler handler = new InboxEnvelopeHandler() {
            @Override public boolean handles(String eventType) { return EVENT_TYPE.equals(eventType); }
            @Override public String consumerName() { return "test.RedriveProbeHandler"; }
            @Override public void handle(EventEnvelope envelope) {
                attempts.incrementAndGet();
                throw new RuntimeException("permanently unrecoverable");
            }
        };
        int maxAttempts = 3;
        var redriver = redriver(handler, maxAttempts, 50);

        var container = startContainer(groupId, dltTopic, redriver);
        try (KafkaConsumer<String, String> parkedConsumer = consumer("parked-" + UUID.randomUUID())) {
            parkedConsumer.subscribe(List.of(parkedTopic));
            publishDltRecord(dltTopic, json.writeValueAsString(probe()), OWN_GROUP);

            ConsumerRecord<String, String> parked = awaitOneRecord(parkedConsumer, Duration.ofSeconds(30));
            assertThat(attempts.get())
                .withFailMessage("record should have been re-applied max-attempts times before parking")
                .isEqualTo(maxAttempts);
            assertThat(headerValue(parked, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo(OWN_GROUP);
            assertThat(headerValue(parked, DltRedriver.HEADER_REDRIVE_ATTEMPTS))
                .isEqualTo(String.valueOf(maxAttempts));
            awaitCommittedOffset(groupId, dltTopic, 1L);
        } finally {
            container.stop();
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private DltRedriver redriver(InboxEnvelopeHandler handler, int maxAttempts, long delayMs) {
        var dispatcher = new KafkaInboxDispatcher(List.of(handler), json);
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
            producerProps(), new StringSerializer(), new StringSerializer()));
        return new DltRedriver(dispatcher, template, OWN_GROUP, maxAttempts, delayMs);
    }

    private KafkaMessageListenerContainer<String, String> startContainer(
            String groupId, String dltTopic, DltRedriver redriver) {
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var consumerFactory = new DefaultKafkaConsumerFactory<>(
            consumerProps, new StringDeserializer(), new StringDeserializer());

        ContainerProperties containerProps = new ContainerProperties(dltTopic);
        containerProps.setGroupId(groupId);
        containerProps.setMessageListener((MessageListener<String, String>) redriver::onDltMessage);

        var container = new KafkaMessageListenerContainer<>(consumerFactory, containerProps);
        container.setBeanName("redrive-it-" + groupId);
        container.start();
        return container;
    }

    private void publishDltRecord(String topic, String value, String originalConsumerGroup) throws Exception {
        try (var producer = new KafkaProducer<>(
                producerProps(), new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, null, UUID.randomUUID().toString(), value);
            record.headers().add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                originalConsumerGroup.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
    }

    private EventEnvelope probe() {
        return new EventEnvelope(
            UUID.randomUUID(), "Test", UUID.randomUUID(), EVENT_TYPE, 1,
            "{}", null, null, null, null, null);
    }

    private ConsumerRecord<String, String> awaitOneRecord(
            KafkaConsumer<String, String> consumer, Duration timeout) {
        var ref = new java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, String>>();
        await().atMost(timeout).pollInterval(Duration.ofMillis(250)).until(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            if (records.isEmpty()) {
                return false;
            }
            ref.set(records.iterator().next());
            return true;
        });
        return ref.get();
    }

    private void assertNothingFor(KafkaConsumer<String, String> consumer, Duration window) {
        long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline) {
            assertThat(consumer.poll(Duration.ofMillis(250)).count())
                .withFailMessage("expected no parked record").isZero();
        }
    }

    private void awaitCommittedOffset(String groupId, String topic, long expected) {
        try (AdminClient admin = adminClient()) {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    OffsetAndMetadata committed = admin
                        .listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata().get()
                        .get(new TopicPartition(topic, 0));
                    assertThat(committed).withFailMessage("offset not committed yet").isNotNull();
                    assertThat(committed.offset()).isEqualTo(expected);
                });
        }
    }

    private void createTopic(String topic) throws Exception {
        try (AdminClient admin = adminClient()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
    }

    private KafkaConsumer<String, String> consumer(String groupId) {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }

    private Map<String, Object> producerProps() {
        return Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    }

    private AdminClient adminClient() {
        return AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
