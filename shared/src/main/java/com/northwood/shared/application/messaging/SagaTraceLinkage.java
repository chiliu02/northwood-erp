package com.northwood.shared.application.messaging;

import com.northwood.shared.domain.Assert;

/**
 * §1D.6 — how the async saga continuation relates to the originating request
 * trace. Selected per environment via {@code northwood.tracing.saga-linkage}
 * and applied by {@code OutboxDrainer} when it publishes each outbox row (using
 * the trace context captured into the row's {@code headers} at append time).
 *
 * <ul>
 *   <li>{@link #PARENT_CHILD} — the publish span (and therefore the Kafka
 *       producer span + downstream consumer spans) is a <em>child</em> of the
 *       originating request span: one end-to-end waterfall. Trades bounded
 *       traces for completeness — a single order's trace absorbs the whole saga
 *       lifecycle, long after the root request span finished.</li>
 *   <li>{@link #SPAN_LINK} <b>(default)</b> — the publish span stays in its own
 *       (drain-tick) trace but carries a {@code Link} back to the originating
 *       request trace. Bounded traces, OTel-idiomatic for messaging, one-click
 *       navigation between the two traces in Grafana.</li>
 *   <li>{@link #BOTH} — restore the parent <em>and</em> add the link
 *       (belt-and-suspenders; the link is redundant within one trace but
 *       survives independent sampling of the two halves).</li>
 *   <li>{@link #OFF} — neither: the messaging trace is rooted at the drain tick
 *       and correlated only by the stamped {@code traceparent} header (the
 *       pre-§1D.6 behaviour).</li>
 * </ul>
 */
public enum SagaTraceLinkage {

    OFF(false, false),
    SPAN_LINK(false, true),
    PARENT_CHILD(true, false),
    BOTH(true, true);

    private final boolean restoresParent;
    private final boolean addsLink;

    SagaTraceLinkage(boolean restoresParent, boolean addsLink) {
        this.restoresParent = restoresParent;
        this.addsLink = addsLink;
    }

    /** True when the publish span should be reparented onto the originating request trace. */
    public boolean restoresParent() {
        return restoresParent;
    }

    /** True when the publish span should carry a {@code Link} to the originating request trace. */
    public boolean addsLink() {
        return addsLink;
    }

    /** Parse the {@code northwood.tracing.saga-linkage} property; hyphen / underscore both accepted. */
    public static SagaTraceLinkage fromProperty(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase().replace('_', '-');
        return switch (normalized) {
            case "off" -> OFF;
            case "span-link" -> SPAN_LINK;
            case "parent-child" -> PARENT_CHILD;
            case "both" -> BOTH;
            default -> throw Assert.unknownValue("northwood.tracing.saga-linkage", value);
        };
    }
}
