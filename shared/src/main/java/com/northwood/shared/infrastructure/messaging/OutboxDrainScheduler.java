package com.northwood.shared.infrastructure.messaging;

import com.northwood.shared.application.outbox.OutboxDrainer;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Driving adapter that fires {@link OutboxDrainer#drain()} on a fixed schedule.
 * Holds the framework-coupled {@code @Scheduled} timer — the infrastructure
 * concern — so the drainer itself stays a plain application orchestrator. The
 * producer-side analog of the consumer-side {@code KafkaInboxDispatcher}'s
 * {@code @KafkaListener} trigger (both messaging triggers live under
 * {@code shared.infrastructure.messaging}).
 *
 * <p>Registered per service in the {@code <Service>OutboxConfig} under
 * {@code @Profile("kafka")}: under the default {@code dev} profile no scheduler
 * bean exists, so the outbox accumulates undrained (the correct single-JVM
 * showcase behaviour). Cadence is {@code northwood.outbox.poll-interval}
 * (default 1s); requires {@code @EnableScheduling} on the service application.
 *
 * <p><b>Risk 1 — why this is a separate bean from the drainer; do not merge.</b>
 * {@code tick()} calls {@link OutboxDrainer#drain()} as a cross-bean call, which
 * is what lets the drainer's {@code @Transactional} fire through its Spring
 * proxy. Inlining {@code new OutboxDrainer(...)} here, or collapsing both into a
 * single bean (so {@code @Scheduled} and {@code @Transactional} share one object
 * → self-invocation), silently drops that transaction — and with it the
 * {@code FOR UPDATE SKIP LOCKED} batch lock that stops concurrent drains from
 * double-publishing. See {@link OutboxDrainer#drain()}.
 *
 * <p><b>Risk 2 — why this is a standalone class, not a {@code @Scheduled} method
 * on the {@code <Service>OutboxConfig}.</b> Folding the trigger into the
 * {@code @Configuration} would require field-self-injecting the drainer bean the
 * config itself defines, plus a {@code @Scheduled} method on a CGLIB-proxied
 * {@code @Configuration} under {@code @Profile} — stacking the project's
 * documented CGLIB / {@code @Configuration} / {@code @Profile} gotchas, and
 * failing (if it does) only at runtime under the {@code kafka} profile, which no
 * test exercises. A plain bean with one {@code @Scheduled} method sidesteps all
 * of that.
 */
public class OutboxDrainScheduler {

    private final OutboxDrainer drainer;

    public OutboxDrainScheduler(OutboxDrainer drainer) {
        this.drainer = drainer;
    }

    @Scheduled(fixedDelayString = "${northwood.outbox.poll-interval:1000}")
    public void tick() {
        drainer.drain();
    }
}
