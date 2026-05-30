package com.northwood.shared.infrastructure.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
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
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-broker tests pinning the consumer delivery + offset-commit contracts the
 * inbox relies on. Documented in {@code docs/messaging.md} → <em>Consumer-side
 * idempotency</em> and the <em>failure-mode matrix</em>:
 *
 * <ul>
 *   <li><b>offset commits only after the listener returns successfully</b> — a
 *       handler exception aborts the commit and the record is redelivered
 *       (re-seek), so a crash between {@code alreadyProcessed} and
 *       {@code recordProcessed} reprocesses cleanly on recovery;</li>
 *   <li><b>malformed envelope is skipped</b> — a value that can't be parsed into
 *       an {@link EventEnvelope} is logged and acked (poison-pill avoidance),
 *       not redelivered (the one path where a failure does NOT redeliver);</li>
 *   <li><b>retryable failure is dead-lettered after the backoff budget</b> — a
 *       transient (retryable) exception is retried under the
 *       {@link org.springframework.util.backoff.ExponentialBackOff} and, once the
 *       budget is exhausted, routed to {@code <topic>.dlt} with the original
 *       offset committed, so the consumer is not stuck looping forever;</li>
 *   <li><b>non-retryable failure is dead-lettered without retry</b> — a poison
 *       exception in {@link KafkaMessagingAutoConfiguration#NOT_RETRYABLE} skips
 *       the backoff entirely and dead-letters on the first failure (§2.28 Tier
 *       1.A classification).</li>
 * </ul>
 *
 * <p><strong>Fidelity.</strong> Production uses Spring Boot's auto-configured
 * {@code ConcurrentKafkaListenerContainerFactory} (see
 * {@link KafkaMessagingAutoConfiguration}). Here the container is wired by hand
 * so the tests need only a broker — no Spring/datasource context — but they
 * reproduce the settings that govern offset commits: {@code enable.auto.commit=
 * false} (container-managed commits; Spring's default) and the default
 * {@link ContainerProperties.AckMode#BATCH}. The offset/malformed tests use a
 * plain {@link DefaultErrorHandler} with {@code FixedBackOff(0L, 3)} (they're
 * orthogonal to error-handler tuning); the two dead-letter tests build the
 * <em>production</em> handler via
 * {@link KafkaMessagingAutoConfiguration#inboxErrorHandler(KafkaTemplate, org.springframework.util.backoff.BackOff)}
 * — same classification + {@code <topic> → <topic>.dlt} recoverer — passing a
 * fast backoff so the retry path dead-letters in well under a second.
 *
 * <p>Each test uses its own topic + consumer group so committed-offset
 * assertions are isolated. Image {@code apache/kafka:4.1.2} (KRaft single-broker
 * defaults) matches {@code docker-compose.yml} and {@code ReorderPolicyChangedSeamIT}.
 */
class KafkaInboxDispatcherDeliveryIT {

    private static final String EVENT_TYPE = "test.DeliveryProbe";

    static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    static {
        Startables.deepStart(KAFKA).join();
    }

    private final ObjectMapper json = new ObjectMapper();

    /**
     * One record; a handler that fails the first delivery (a simulated
     * mid-processing disaster) then succeeds on the redelivery. Two observations
     * pin the whole contract: {@code attempts == 2} (the failed delivery did
     * <em>not</em> commit/skip — the record was redelivered) and the committed
     * offset advances to {@code 1} (committed only after the successful return).
     */
    @Test
    void offset_commits_only_after_the_listener_returns_successfully() throws Exception {
        String topic = "shared.delivery-it.offset";
        String groupId = "offset-" + UUID.randomUUID();
        createTopic(topic);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        InboxEnvelopeHandler handler = countingHandler(attempts, succeeded, /*failFirst*/ true);
        var dispatcher = new KafkaInboxDispatcher(List.of(handler), json);

        var container = startContainer(groupId, topic, dispatcher,
            new DefaultErrorHandler(new FixedBackOff(0L, 3)));
        try {
            publish(topic, json.writeValueAsString(probe()));

            assertThat(succeeded.await(30, TimeUnit.SECONDS))
                .withFailMessage("handler never reprocessed the redelivered record").isTrue();
            assertThat(attempts.get()).isEqualTo(2);
            awaitCommittedOffset(groupId, topic, 1L);
        } finally {
            container.stop();
        }
    }

    /**
     * A value that cannot be parsed into an {@link EventEnvelope} is logged and
     * skipped by {@code KafkaInboxDispatcher.onMessage} (it catches
     * {@code JacksonException} and returns normally). The handler is never
     * invoked and the offset still commits — so a poison payload does NOT block
     * the partition. This is the deliberate exception to "a failure aborts the
     * commit".
     */
    @Test
    void malformed_envelope_is_skipped_and_offset_still_commits() throws Exception {
        String topic = "shared.delivery-it.malformed";
        String groupId = "malformed-" + UUID.randomUUID();
        createTopic(topic);

        AtomicInteger attempts = new AtomicInteger();
        InboxEnvelopeHandler handler = countingHandler(attempts, new CountDownLatch(1), false);
        var dispatcher = new KafkaInboxDispatcher(List.of(handler), json);

        var container = startContainer(groupId, topic, dispatcher,
            new DefaultErrorHandler(new FixedBackOff(0L, 3)));
        try {
            publish(topic, "{ this is not valid json");

            // Offset advances → the record was consumed, skipped, and committed.
            awaitCommittedOffset(groupId, topic, 1L);
            assertThat(attempts.get())
                .withFailMessage("malformed record should never reach a handler").isZero();
        } finally {
            container.stop();
        }
    }

    /**
     * A handler that always throws a <em>retryable</em> exception is retried
     * (re-seek) under the {@link ExponentialBackOff} until the budget is
     * exhausted, then routed to {@code <topic>.dlt} by the production handler's
     * {@link DeadLetterPublishingRecoverer}; the original offset then commits so
     * the consumer moves on. Asserts the DLT record lands, the record was
     * retried (>1 attempt), and the source offset advances. Uses the
     * {@link KafkaMessagingAutoConfiguration#inboxErrorHandler} factory with a
     * fast backoff so the budget runs out in well under a second.
     */
    @Test
    void retryable_failure_is_dead_lettered_after_backoff_then_offset_commits() throws Exception {
        assertDeadLettered("shared.delivery-it.dlt-retryable", "dlt-retryable-" + UUID.randomUUID(),
            new RuntimeException("transient failure"), /*expectRetries*/ true);
    }

    /**
     * A handler that throws a <em>non-retryable</em> exception
     * ({@link IllegalStateException}, listed in
     * {@link KafkaMessagingAutoConfiguration#NOT_RETRYABLE}) skips the backoff
     * entirely: the production handler dead-letters it on the first failure. So
     * a poison record reaches {@code <topic>.dlt} after exactly one handler
     * invocation, the source offset commits, and no retry budget is burned.
     */
    @Test
    void non_retryable_failure_is_dead_lettered_without_retry() throws Exception {
        assertDeadLettered("shared.delivery-it.dlt-poison", "dlt-poison-" + UUID.randomUUID(),
            new IllegalStateException("poison payload"), /*expectRetries*/ false);
    }

    /**
     * Publishes one probe onto {@code topic}, runs it through a container wired
     * with the production error handler (fast backoff) and a handler that always
     * throws {@code toThrow}, and asserts it lands on {@code <topic>.dlt} with the
     * source offset committed. When {@code expectRetries} the handler must have
     * been invoked more than once (the backoff retry path); otherwise exactly
     * once (classified non-retryable → straight to DLT).
     */
    private void assertDeadLettered(
            String topic, String groupId, RuntimeException toThrow, boolean expectRetries) throws Exception {
        String dltTopic = topic + ".dlt";
        createTopic(topic);

        AtomicInteger attempts = new AtomicInteger();
        InboxEnvelopeHandler handler = new InboxEnvelopeHandler() {
            @Override public boolean handles(String eventType) { return EVENT_TYPE.equals(eventType); }
            @Override public String consumerName() { return "test.AlwaysFailsHandler"; }
            @Override public void handle(EventEnvelope envelope) {
                attempts.incrementAndGet();
                throw toThrow;
            }
        };
        var dispatcher = new KafkaInboxDispatcher(List.of(handler), json);
        var errorHandler = KafkaMessagingAutoConfiguration.inboxErrorHandler(dltTemplate(), fastBackOff());

        var container = startContainer(groupId, topic, dispatcher, errorHandler);
        try (KafkaConsumer<String, String> dltConsumer = consumer("dlt-verify-" + UUID.randomUUID())) {
            dltConsumer.subscribe(List.of(dltTopic));
            publish(topic, json.writeValueAsString(probe()));

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> dltConsumer.poll(Duration.ofMillis(500)).count() > 0);
            if (expectRetries) {
                assertThat(attempts.get())
                    .withFailMessage("retryable record should have been retried before dead-lettering")
                    .isGreaterThanOrEqualTo(2);
            } else {
                assertThat(attempts.get())
                    .withFailMessage("non-retryable record should dead-letter on the first failure, no retry")
                    .isEqualTo(1);
            }
            awaitCommittedOffset(groupId, topic, 1L);
        } finally {
            container.stop();
        }
    }

    // ------------------------------------------------------------------
    // Harness — mirrors KafkaMessagingAutoConfiguration's container settings.
    // ------------------------------------------------------------------

    private InboxEnvelopeHandler countingHandler(
            AtomicInteger attempts, CountDownLatch succeeded, boolean failFirst) {
        return new InboxEnvelopeHandler() {
            @Override public boolean handles(String eventType) { return EVENT_TYPE.equals(eventType); }
            @Override public String consumerName() { return "test.DeliveryProbeHandler"; }
            @Override public void handle(EventEnvelope envelope) {
                if (failFirst && attempts.incrementAndGet() == 1) {
                    throw new RuntimeException("simulated mid-processing failure");
                }
                if (!failFirst) {
                    attempts.incrementAndGet();
                }
                succeeded.countDown();
            }
        };
    }

    private EventEnvelope probe() {
        return new EventEnvelope(
            UUID.randomUUID(), "Test", UUID.randomUUID(), EVENT_TYPE, 1,
            "{}", null, null, null, null, null);
    }

    private KafkaMessageListenerContainer<String, String> startContainer(
            String groupId, String topic, KafkaInboxDispatcher dispatcher, DefaultErrorHandler errorHandler) {
        Map<String, Object> consumerProps = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            // Container-managed commits: Spring's listener container defaults
            // this to false; pinned here to make the contract under test explicit.
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );
        var consumerFactory = new DefaultKafkaConsumerFactory<>(
            consumerProps, new StringDeserializer(), new StringDeserializer());

        ContainerProperties containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(groupId);
        containerProps.setMessageListener((MessageListener<String, String>) dispatcher::onMessage);
        // AckMode left at the default (BATCH): commit after the poll's records
        // are processed without a thrown exception.

        var container = new KafkaMessageListenerContainer<>(consumerFactory, containerProps);
        container.setBeanName("delivery-it-" + groupId);
        container.setCommonErrorHandler(errorHandler);
        container.start();
        return container;
    }

    private void publish(String topic, String value) throws Exception {
        try (var producer = new KafkaProducer<>(
                producerProps(), new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), value)).get();
        }
    }

    /** Template the production {@code inboxErrorHandler} recoverer publishes the DLT copy through. */
    private KafkaTemplate<String, String> dltTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
            producerProps(), new StringSerializer(), new StringSerializer()));
    }

    /**
     * Same {@link ExponentialBackOff} shape as the production bean, compressed to
     * sub-second so the retryable dead-letter path exhausts its budget quickly:
     * ~50ms, 100ms, 200ms (capped) retries, giving up after ~500ms total → DLT.
     */
    private ExponentialBackOff fastBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(50L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(200L);
        backOff.setMaxElapsedTime(500L);
        return backOff;
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
}
