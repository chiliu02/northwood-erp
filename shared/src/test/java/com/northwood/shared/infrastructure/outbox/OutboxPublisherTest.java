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
        publisher = new OutboxPublisher(outbox, bus, "sales");
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
        publisher = new OutboxPublisher(outbox, bus, "purchasing");
        OutboxRow row = pendingRow("purchasing.PurchaseOrderCreated");
        when(outbox.findPending(Mockito.anyInt())).thenReturn(List.of(row));

        publisher.drain();

        ArgumentCaptor<EventEnvelope> cap = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(bus).publish(cap.capture());
        assertThat(cap.getValue().headers().get(EventEnvelope.HEADER_SOURCE_SERVICE))
            .isEqualTo("purchasing");
    }
}
