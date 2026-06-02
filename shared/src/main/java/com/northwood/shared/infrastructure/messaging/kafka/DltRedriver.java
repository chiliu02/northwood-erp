package com.northwood.shared.infrastructure.messaging.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Per-service auto-redrive for dead-lettered records. Turns a
 * {@code <topic>.dlt} from a manual graveyard into an auto-retry tier: each
 * consuming service runs <em>its own</em> redriver (consumer group
 * {@code <service>-dlt-redriver}) that re-applies only the records <em>it</em>
 * failed on, and parks the genuinely-unrecoverable ones in a terminal store.
 *
 * <h2>Header for routing, partition for concurrency</h2>
 *
 * A single {@code <topic>.dlt} is shared by every consumer of {@code <topic>}
 * (e.g. {@code sales.events.dlt} collects failures from inventory, finance,
 * manufacturing, reporting, …). So the redriver can't key off the topic — it
 * filters on the {@link KafkaHeaders#DLT_ORIGINAL_CONSUMER_GROUP
 * kafka_dlt-original-consumer-group} header that Spring's
 * {@code DeadLetterPublishingRecoverer} stamps on every DLT record: a record is
 * mine iff that header equals my own consumer group; otherwise another service's
 * redriver owns it and I skip it (commit, no-op). This keeps redrive
 * <strong>per-service</strong> — no cross-service shared consumer group — so the
 * "each service owns its own group, ready to split" invariant holds.
 *
 * <p>The DLT record's <em>key</em> stays the original {@code aggregateId} (the
 * recoverer preserves it), so partitions keep doing their real job —
 * per-aggregate ordering + parallelism. This listener uses ordinary group
 * subscription with a configurable {@code concurrency}, so partitions are
 * consumed in parallel: a slow (blocking) redrive of one partition/topic never
 * stalls the others. (Routing by partition index instead would have forced
 * manual partition assignment and forfeited that group-managed parallelism.)
 *
 * <h2>Re-apply in place, bounded, then park</h2>
 *
 * For a record that is mine, the redriver re-dispatches it through the same
 * {@link KafkaInboxDispatcher#onMessage} fan-out the live path uses — so the
 * inbox dedup makes it safe: handlers that already succeeded short-circuit, and
 * only the handler that originally failed re-runs. No republish to the source
 * topic, so already-succeeded consumers are never re-disturbed.
 *
 * <p>Retries happen <strong>within a single listener invocation</strong>: up to
 * {@code max-attempts}, sleeping {@code delay} between them, then — if still
 * failing — the record is published to a terminal {@code <topic>.dlt.parked}
 * store for ops. Because the method <em>always returns normally</em> (it catches
 * every re-apply failure), the offset commits and the
 * {@code DefaultErrorHandler} never fires on it — so there is no
 * {@code .dlt.dlt} recursion, and the {@code .+\.dlt} subscription pattern does
 * not match {@code .dlt.parked}, so parked records are never re-read.
 *
 * <p><b>Blocking-delay budget.</b> The per-record block is
 * {@code (max-attempts - 1) × delay} (default {@code 4 × 10s = 40s}), held on
 * the partition's consumer thread with {@code max.poll.records=1}, so it stays
 * well under {@code max.poll.interval.ms} (5 min) — keep
 * {@code (max-attempts - 1) × delay} comfortably below that ceiling when tuning.
 *
 * <p>Registered only when {@code northwood.kafka.dlt.redrive.enabled=true} (set
 * in the consuming services' {@code application-kafka.yml}); off under the
 * default {@code dev} profile.
 */
public class DltRedriver {

    private static final Logger log = LoggerFactory.getLogger(DltRedriver.class);

    /** Terminal-store suffix appended to a DLT topic once the redrive budget is exhausted. */
    static final String PARKED_SUFFIX = ".parked";
    /** Header on a parked record: how many redrive attempts it exhausted. */
    static final String HEADER_REDRIVE_ATTEMPTS = "northwood-dlt-redrive-attempts";
    /** Header on a parked record: the last failure that caused it to be parked. */
    static final String HEADER_REDRIVE_LAST_ERROR = "northwood-dlt-redrive-last-error";

    private final KafkaInboxDispatcher dispatcher;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String ownGroup;
    private final int maxAttempts;
    private final long delayMs;

    public DltRedriver(
        KafkaInboxDispatcher dispatcher,
        KafkaTemplate<String, String> kafkaTemplate,
        String ownGroup,
        int maxAttempts,
        long delayMs
    ) {
        this.dispatcher = dispatcher;
        this.kafkaTemplate = kafkaTemplate;
        this.ownGroup = ownGroup;
        this.maxAttempts = maxAttempts;
        this.delayMs = delayMs;
        log.info("DltRedriver wired for group '{}' (maxAttempts={}, delayMs={})",
            ownGroup, maxAttempts, delayMs);
    }

    @KafkaListener(
        topicPattern = "${northwood.kafka.dlt.redrive.topic-pattern:.+\\.dlt}",
        groupId = "${spring.kafka.consumer.group-id}-dlt-redriver",
        concurrency = "${northwood.kafka.dlt.redrive.concurrency:3}",
        properties = { "max.poll.records=1", "metadata.max.age.ms=30000" }
    )
    public void onDltMessage(ConsumerRecord<String, String> record) {
        String origGroup = header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        if (origGroup == null || !origGroup.equals(ownGroup)) {
            // Not this service's failure (or not a Spring-produced DLT record).
            // Another service's per-service redriver owns it — skip + commit.
            return;
        }

        RuntimeException last = null;
        int attemptsMade = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                sleep(delayMs);
            }
            attemptsMade = attempt;
            try {
                // Re-apply via the live fan-out: already-succeeded handlers
                // dedup-skip, the failed one retries. Throws iff it fails again.
                dispatcher.onMessage(record);
                log.info("redrive of {}-{}@{} (group {}) succeeded on attempt {}/{}",
                    record.topic(), record.partition(), record.offset(), origGroup, attempt, maxAttempts);
                return;
            } catch (RuntimeException e) {
                last = e;
                // A deterministic failure (the record itself violates an invariant
                // — a CHECK constraint, a malformed payload, a type bug) cannot
                // change on re-apply of the identical record, so burning the rest
                // of the redrive budget is pointless: park it now. Crucially this
                // is NARROWER than the consumer's NOT_RETRYABLE set — redrive is
                // exactly the recovery tier for transient-looking DataIntegrity
                // failures (an FK violation because a prerequisite projection row
                // hasn't arrived yet succeeds on a later attempt), so those keep
                // retrying. See isDeterministic for the CHECK-vs-FK split.
                if (isDeterministic(e)) {
                    log.warn("redrive of {}-{}@{} (group {}) failed deterministically on attempt {}/{}; "
                            + "parking without further retries: {}",
                        record.topic(), record.partition(), record.offset(), origGroup,
                        attempt, maxAttempts, e.toString());
                    break;
                }
                log.warn("redrive attempt {}/{} of {}-{}@{} (group {}) failed: {}",
                    attempt, maxAttempts, record.topic(), record.partition(), record.offset(),
                    origGroup, e.toString());
            }
        }
        park(record, last, attemptsMade);
    }

    /**
     * Is this failure deterministic — guaranteed to recur on a re-apply of the
     * identical record, so not worth the rest of the redrive budget? Walks the
     * cause chain and treats as deterministic:
     *
     * <ul>
     *   <li>a {@link java.sql.SQLException} whose SQLState is a CHECK violation
     *       ({@code 23514}), NOT-NULL violation ({@code 23502}), exclusion
     *       violation ({@code 23P01}), or any data-exception ({@code 22*}, e.g.
     *       numeric overflow / bad text) — the row itself breaks an invariant;</li>
     *   <li>a malformed-payload ({@link tools.jackson.core.JacksonException}) or
     *       a programming-shape bug ({@link NullPointerException} /
     *       {@link ClassCastException}).</li>
     * </ul>
     *
     * <p>Deliberately NOT deterministic (keep retrying — these are the cases the
     * redrive tier exists to recover): FK violations ({@code 23503}) and unique
     * violations ({@code 23505}) — typically a prerequisite/ordering timing issue
     * that resolves once the awaited row arrives; and {@link IllegalStateException}
     * (the {@code Assert.state} idiom covers both genuine invariant breaks and
     * "no saga row yet" prerequisite-timing, so it stays retryable rather than
     * risk parking a recoverable record).
     */
    static boolean isDeterministic(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.equals("23514") || state.equals("23502")
                    || state.equals("23P01") || state.startsWith("22"))) {
                    return true;
                }
            }
            if (c instanceof tools.jackson.core.JacksonException
                || c instanceof NullPointerException
                || c instanceof ClassCastException) {
                return true;
            }
            if (c.getCause() == c) {
                break;   // guard against a self-referential cause chain
            }
        }
        return false;
    }

    private void park(ConsumerRecord<String, String> record, RuntimeException lastError, int attemptsMade) {
        String parkedTopic = record.topic() + PARKED_SUFFIX;
        ProducerRecord<String, String> parked =
            new ProducerRecord<>(parkedTopic, null, record.key(), record.value());
        // Carry the original DLT diagnostics forward (original consumer group,
        // exception fqcn/message, original topic/offset) so a parked record is
        // self-describing for ops.
        record.headers().forEach(h -> parked.headers().add(h));
        // Actual attempts made (1 for a deterministic park, up to maxAttempts otherwise).
        parked.headers().add(HEADER_REDRIVE_ATTEMPTS,
            String.valueOf(attemptsMade).getBytes(StandardCharsets.UTF_8));
        if (lastError != null) {
            parked.headers().add(HEADER_REDRIVE_LAST_ERROR,
                lastError.toString().getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(parked).join();
        log.error("redrive of {}-{}@{} gave up after {} attempt(s); parked in {} (last error: {})",
            record.topic(), record.partition(), record.offset(), attemptsMade, parkedTopic,
            lastError == null ? "n/a" : lastError.toString());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during redrive delay", e);
        }
    }
}
