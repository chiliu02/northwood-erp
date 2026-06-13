package com.northwood.reporting.application.inbox.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.reporting.application.inbox.CustomerDashboardProjection;
import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.sales.domain.events.CustomerDeactivated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CustomerDeactivatedHandlerTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Instant DEACTIVATED_AT = Instant.parse("2026-05-14T03:15:00Z");

    @Mock InboxPort inbox;
    @Mock CustomerDashboardProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private CustomerDeactivatedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CustomerDeactivatedHandler(inbox, projection, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        CustomerDeactivated payload = new CustomerDeactivated(eventId, CUSTOMER, "duplicate record", DEACTIVATED_AT);
        return new EventEnvelope(
            eventId, SalesAggregateTypes.CUSTOMER, CUSTOMER,
            CustomerDeactivated.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_flags_customer_inactive() {
        handler.handle(event());

        verify(projection).recordCustomerDeactivated(eq(CUSTOMER), eq(DEACTIVATED_AT));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(CustomerDeactivatedHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).recordCustomerDeactivated(any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
