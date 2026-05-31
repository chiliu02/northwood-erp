package com.northwood.shared.application.saga;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.UUID;

/**
 * §1D.9 — records one **milestone span** per saga transition, building the
 * saga's end-to-end overview without a long-lived root span.
 *
 * <p>Each milestone is a standalone span (its own one-span trace,
 * {@code setNoParent}) named after the saga state entered, tagged with the
 * durable business keys ({@code northwood.saga_id}, {@code northwood.saga_type},
 * and {@code northwood.sales_order_id} as the cross-saga key when an originating
 * order is known), and carrying a {@code Link} to the trace that was current at
 * the transition — the detail trace of the triggering action/event. The span is
 * started and ended immediately.
 *
 * <p>The "saga view" is then a TraceQL search, not a single nested trace:
 * {@code { .northwood.saga_id = "…" }} for one saga's milestone timeline, or
 * {@code { .northwood.sales_order_id = "…" }} for an order plus the sub-sagas it
 * triggered — each milestone one click (the link) from its detail. Anchoring on
 * the durable {@code saga_id} (which lives on the saga row) rather than a
 * long-lived root traceId keeps "find everything for this saga" independent of
 * Tempo's block retention.
 *
 * <p>This is a self-observability concern (a structured marker of the
 * transition, alongside the manager/worker's own log line), recorded explicitly
 * by the side-effect owner — the {@code *SagaManager.applyXxx} for inbox-driven
 * transitions and the {@code *SagaWorker.advance} for worker-driven ones.
 * NOOP-safe: a {@code null} or {@link Tracer#NOOP} tracer makes it a no-op.
 */
public final class SagaMilestone {

    public static final String TAG_SAGA_ID = "northwood.saga_id";
    public static final String TAG_SAGA_TYPE = "northwood.saga_type";
    public static final String TAG_SALES_ORDER_ID = "northwood.sales_order_id";

    private SagaMilestone() {}

    /**
     * Record the milestone for a transition into {@code state}. {@code salesOrderId}
     * is the cross-saga key — pass it when the saga has an originating sales order
     * (always for the SO fulfilment saga; for an SO-shortage-driven work order),
     * or {@code null} when there is none (pool / reorder-point replenishment).
     */
    public static void record(Tracer tracer, String sagaType, UUID sagaId, String state, UUID salesOrderId) {
        if (tracer == null || state == null) {
            return;
        }
        Span.Builder builder = tracer.spanBuilder()
            .name("saga." + state)
            .setNoParent()
            .tag(TAG_SAGA_TYPE, sagaType)
            .tag(TAG_SAGA_ID, String.valueOf(sagaId));
        if (salesOrderId != null) {
            builder.tag(TAG_SALES_ORDER_ID, salesOrderId.toString());
        }
        Span current = tracer.currentSpan();
        if (current != null && current.context() != null) {
            builder.addLink(new Link(current.context()));
        }
        builder.start().end();
    }
}
