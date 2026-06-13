package com.northwood.finance.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.JournalEntryService.LineCost;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.inventory.domain.events.RawMaterialsReserved.ReservedComponent;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RawMaterialsReservedWipHandlerTest {

    private static final UUID WORK_ORDER = UUID.randomUUID();
    private static final UUID RM_A = UUID.randomUUID();
    private static final UUID RM_B = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock JournalEntryService journals;
    @Mock ProductCardLookup productCards;
    @Mock WorkOrderWipProjection workOrderWip;

    private final ObjectMapper json = new ObjectMapper();
    private RawMaterialsReservedWipHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RawMaterialsReservedWipHandler(inbox, journals, productCards, workOrderWip, json);
    }

    private EventEnvelope event(String status, ReservedComponent... components) {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        RawMaterialsReserved payload = new RawMaterialsReserved(
            eventId, reservationId, WORK_ORDER, reservationId, status,
            List.of(components), Instant.now());
        return new EventEnvelope(
            eventId, "StockReservation", reservationId,
            RawMaterialsReserved.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    private ReservedComponent reserved(UUID productId, String reservedQty) {
        return new ReservedComponent(UUID.randomUUID(), productId,
            new BigDecimal(reservedQty), new BigDecimal(reservedQty), BigDecimal.ZERO,
            RawMaterialsReserved.STATUS_RESERVED);
    }

    @Test void fully_reserved_charges_wip_and_posts_at_standard_cost() {
        when(productCards.findStandardCost(RM_A)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(productCards.findStandardCost(RM_B)).thenReturn(Optional.of(new BigDecimal("2.50")));
        when(workOrderWip.chargeRawMaterials(eq(WORK_ORDER), any())).thenReturn(true);

        handler.handle(event(RawMaterialsReserved.STATUS_RESERVED,
            reserved(RM_A, "3"), reserved(RM_B, "2")));   // 3×4.00 + 2×2.50 = 17.00

        ArgumentCaptor<BigDecimal> charged = ArgumentCaptor.forClass(BigDecimal.class);
        verify(workOrderWip).chargeRawMaterials(eq(WORK_ORDER), charged.capture());
        assertThat(charged.getValue()).isEqualByComparingTo("17.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LineCost>> lineCosts = ArgumentCaptor.forClass(List.class);
        verify(journals).postWorkInProgressCharge(
            eq(WORK_ORDER), any(), lineCosts.capture(), eq(Currencies.BASE_CURRENCY), any());
        BigDecimal total = lineCosts.getValue().stream()
            .map(LineCost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("17.00");
        verify(inbox).recordProcessed(any());
    }

    @Test void partially_reserved_does_not_charge_or_post() {
        handler.handle(event(RawMaterialsReserved.STATUS_PARTIALLY_RESERVED, reserved(RM_A, "1")));

        verifyNoInteractions(journals);
        verify(workOrderWip, never()).chargeRawMaterials(any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void already_charged_skips_the_journal_post() {
        when(productCards.findStandardCost(RM_A)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(workOrderWip.chargeRawMaterials(eq(WORK_ORDER), any())).thenReturn(false);

        handler.handle(event(RawMaterialsReserved.STATUS_RESERVED, reserved(RM_A, "3")));

        verify(journals, never()).postWorkInProgressCharge(any(), any(), any(), any(), any());
    }

    @Test void zero_standard_cost_skips_charge_and_post() {
        when(productCards.findStandardCost(RM_A)).thenReturn(Optional.empty());

        handler.handle(event(RawMaterialsReserved.STATUS_RESERVED, reserved(RM_A, "3")));

        verify(workOrderWip, never()).chargeRawMaterials(any(), any());
        verifyNoInteractions(journals);
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(RawMaterialsReserved.STATUS_RESERVED, reserved(RM_A, "1"));
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(RawMaterialsReservedWipHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verifyNoInteractions(workOrderWip);
        verify(inbox, never()).recordProcessed(any());
    }
}
