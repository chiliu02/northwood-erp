package com.northwood.shared.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxAppenderTest {

    record SampleEvent(UUID eventId, UUID aggregateId, String label, Instant occurredAt) implements DomainEvent {
        @Override public String eventType() { return "test.SampleEvent"; }
    }

    @Mock OutboxPort outbox;
    @Mock CurrentUserAccessor currentUser;

    private final ObjectMapper json = new ObjectMapper();
    private OutboxAppender appender;

    @BeforeEach
    void setUp() {
        appender = new OutboxAppender(outbox, json, currentUser);
    }

    @Test void maps_event_fields_onto_pending_row_and_stamps_current_user() {
        when(currentUser.currentUsername()).thenReturn(Optional.of("linda"));
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        SampleEvent event = new SampleEvent(eventId, aggregateId, "hello", Instant.now());

        appender.append(event, "Sample");

        ArgumentCaptor<OutboxRow> cap = ArgumentCaptor.forClass(OutboxRow.class);
        verify(outbox).appendPending(cap.capture());
        OutboxRow row = cap.getValue();
        assertThat(row.getOutboxMessageId()).isEqualTo(eventId);   // outbox id = event id
        assertThat(row.getAggregateType()).isEqualTo("Sample");
        assertThat(row.getAggregateId()).isEqualTo(aggregateId);
        assertThat(row.getEventType()).isEqualTo("test.SampleEvent");
        assertThat(row.getEventVersion()).isEqualTo(1);            // DomainEvent default
        assertThat(row.getStatus()).isEqualTo(OutboxRow.PENDING);
        assertThat(row.getActorUserId()).isEqualTo("linda");
        assertThat(row.getPayload()).contains("hello");
    }

    @Test void stamps_null_actor_when_no_authenticated_user() {
        when(currentUser.currentUsername()).thenReturn(Optional.empty());
        SampleEvent event = new SampleEvent(UUID.randomUUID(), UUID.randomUUID(), "x", Instant.now());

        appender.append(event, "Sample");

        ArgumentCaptor<OutboxRow> cap = ArgumentCaptor.forClass(OutboxRow.class);
        verify(outbox).appendPending(cap.capture());
        assertThat(cap.getValue().getActorUserId()).isNull();
    }

    @Test void serialisation_failure_translates_to_illegal_state_and_appends_nothing() {
        ObjectMapper boomJson = mock(ObjectMapper.class);
        JacksonException boom = mock(JacksonException.class);
        when(boomJson.writeValueAsString(any())).thenThrow(boom);
        OutboxAppender failing = new OutboxAppender(outbox, boomJson, currentUser);
        SampleEvent event = new SampleEvent(UUID.randomUUID(), UUID.randomUUID(), "x", Instant.now());

        assertThatThrownBy(() -> failing.append(event, "Sample"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to serialise")
            .hasMessageContaining("test.SampleEvent");
        verify(outbox, never()).appendPending(any());
    }
}
