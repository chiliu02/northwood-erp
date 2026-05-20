package com.northwood.shared.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OutboxPublisher}. The drain method is the only
 * @Transactional method; coverage exercises:
 *
 * <ul>
 *   <li>empty pending batch → no bus publish, no save.</li>
 *   <li>full batch → each row published, marked published, saved.</li>
 *   <li>partial failure → failed row marked failed (with error), siblings
 *       still drain successfully.</li>
 *   <li>{@code source-service} header stamped on the envelope from the
 *       publisher's {@code serviceName}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock OutboxPort outbox;
    @Mock EventPublisher bus;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outbox, bus, "sales", Tracer.NOOP, ObservationRegistry.NOOP);
    }

    private static OutboxRow pendingRow(String eventType) {
        return OutboxRow.pending(
            UUID.randomUUID(), "SalesOrder", UUID.randomUUID(),
            eventType, 1, "{}", null, null, null, null
        );
    }

    @Test void drain_no_pending_rows_publishes_nothing() {
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of());

        publisher.drain();

        verify(bus, never()).publish(any());
        verify(outbox, never()).save(any());
    }

    @Test void drain_publishes_each_row_marks_published_and_saves() {
        OutboxRow r1 = pendingRow("sales.SalesOrderPlaced");
        OutboxRow r2 = pendingRow("sales.StockReservationRequested");
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of(r1, r2));

        publisher.drain();

        ArgumentCaptor<EventEnvelope> envCap = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(bus, times(2)).publish(envCap.capture());
        List<EventEnvelope> envelopes = envCap.getAllValues();
        assertThat(envelopes).extracting(EventEnvelope::eventType)
            .containsExactly("sales.SalesOrderPlaced", "sales.StockReservationRequested");
        // source-service header should be stamped.
        assertThat(envelopes.get(0).headers().get(EventEnvelope.HEADER_SOURCE_SERVICE)).isEqualTo("sales");

        // Both rows transitioned to published and saved.
        assertThat(r1.getStatus()).isEqualTo("published");
        assertThat(r2.getStatus()).isEqualTo("published");
        verify(outbox, times(2)).save(any(OutboxRow.class));
    }

    @Test void drain_partial_failure_marks_failed_row_and_continues() {
        OutboxRow r1 = pendingRow("event.A");
        OutboxRow r2 = pendingRow("event.B");
        OutboxRow r3 = pendingRow("event.C");
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of(r1, r2, r3));

        // r2 throws on publish; r1 and r3 succeed. Using doAnswer with an
        // explicit predicate is more robust than argThat with void methods.
        Mockito.doAnswer(inv -> {
            EventEnvelope env = inv.getArgument(0);
            if ("event.B".equals(env.eventType())) {
                throw new RuntimeException("kafka kaboom");
            }
            return null;
        }).when(bus).publish(any());

        publisher.drain();

        assertThat(r1.getStatus()).isEqualTo("published");
        assertThat(r2.getStatus()).isEqualTo("failed");
        assertThat(r2.getLastError()).contains("kafka kaboom");
        assertThat(r3.getStatus()).isEqualTo("published");

        // Each row's outcome saved.
        InOrder inOrder = Mockito.inOrder(outbox);
        inOrder.verify(outbox).save(r1);
        inOrder.verify(outbox).save(r2);
        inOrder.verify(outbox).save(r3);
    }

    @Test void drain_uses_service_name_in_envelope_header() {
        publisher = new OutboxPublisher(outbox, bus, "purchasing", Tracer.NOOP, ObservationRegistry.NOOP);
        OutboxRow row = pendingRow("purchasing.PurchaseOrderCreated");
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of(row));

        publisher.drain();

        ArgumentCaptor<EventEnvelope> cap = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(bus).publish(cap.capture());
        assertThat(cap.getValue().headers().get(EventEnvelope.HEADER_SOURCE_SERVICE))
            .isEqualTo("purchasing");
    }

    @Test void drain_stamps_w3c_traceparent_when_tracer_has_current_span() {
        // §1D.2: when a Tracer with an active span is supplied, the publisher
        // serialises the trace context as a W3C traceparent string into the
        // envelope's headers map. The BFF events aggregator (§1D.4) reads this
        // header to render the SPA's "↗ trace" affordance.
        Tracer tracer = Mockito.mock(Tracer.class);
        io.micrometer.tracing.Span span = Mockito.mock(io.micrometer.tracing.Span.class);
        io.micrometer.tracing.TraceContext ctx = Mockito.mock(io.micrometer.tracing.TraceContext.class);
        Mockito.when(tracer.currentSpan()).thenReturn(span);
        Mockito.when(span.context()).thenReturn(ctx);
        Mockito.when(ctx.traceId()).thenReturn("00112233445566778899aabbccddeeff");
        Mockito.when(ctx.spanId()).thenReturn("0011223344556677");
        Mockito.when(ctx.sampled()).thenReturn(Boolean.TRUE);

        publisher = new OutboxPublisher(outbox, bus, "sales", tracer, ObservationRegistry.NOOP);
        OutboxRow row = pendingRow("sales.SalesOrderPlaced");
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of(row));

        publisher.drain();

        ArgumentCaptor<EventEnvelope> cap = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(bus).publish(cap.capture());
        assertThat(cap.getValue().headers().get(EventEnvelope.HEADER_TRACEPARENT))
            .isEqualTo("00-00112233445566778899aabbccddeeff-0011223344556677-01");
    }

    @Test void drain_omits_traceparent_when_tracer_has_no_current_span() {
        Tracer tracer = Mockito.mock(Tracer.class);
        Mockito.when(tracer.currentSpan()).thenReturn(null);

        publisher = new OutboxPublisher(outbox, bus, "sales", tracer, ObservationRegistry.NOOP);
        OutboxRow row = pendingRow("sales.SalesOrderPlaced");
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of(row));

        publisher.drain();

        ArgumentCaptor<EventEnvelope> cap = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(bus).publish(cap.capture());
        assertThat(cap.getValue().headers()).doesNotContainKey(EventEnvelope.HEADER_TRACEPARENT);
    }
}
