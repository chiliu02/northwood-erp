package com.northwood.sales.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.sales.application.ProductCardLookup.CatalogPrice;
import com.northwood.sales.application.SalesOrderService.EmptyOrderNotAllowedException;
import com.northwood.sales.application.SalesOrderService.OrderNotAmendableException;
import com.northwood.sales.application.SalesOrderService.OrderVersionConflictException;
import com.northwood.sales.application.dto.AddOrderLineCommand;
import com.northwood.sales.application.dto.RemoveOrderLineCommand;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesOrderServiceAmendTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID LINE_ID = UUID.randomUUID();

    @Mock SalesOrderRepository orders;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock CustomerLookup customers;
    @Mock ProductCardLookup productCards;

    private SalesOrderService service;

    @BeforeEach
    void setUp() {
        service = new SalesOrderService(orders, sagaManager, customers, productCards);
        Mockito.lenient().when(orders.findById(SalesOrderId.of(ORDER_ID)))
            .thenReturn(Optional.of(order(SalesOrder.Status.OPEN, 2L)));
    }

    private static SalesOrder order(SalesOrder.Status status, long version) {
        return SalesOrder.reconstitute(
            SalesOrderId.of(ORDER_ID), "SO-1", CUSTOMER_ID, "CUST-1", "Customer One",
            LocalDate.now(), null, status, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), null, version,
            new ArrayList<>(List.of(new SalesOrderLine(
                LINE_ID, 10, PRODUCT_ID, "SKU-1", "Widget",
                new BigDecimal("1"), new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, SalesOrder.LineStatus.OPEN
            )))
        );
    }

    private static AddOrderLineCommand addCommand(Long expectedVersion) {
        return new AddOrderLineCommand(
            ORDER_ID, expectedVersion, PRODUCT_ID, "SKU-2", "Chair",
            new BigDecimal("2"), new BigDecimal("25.00"), BigDecimal.ZERO
        );
    }

    @Test void addLine_allowed_when_saga_started() {
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("25.00"), Currencies.AUD, null, 0)
        ));
        when(sagaManager.currentState(ORDER_ID)).thenReturn(Optional.of(SalesOrderFulfilmentSaga.STARTED));

        service.addLine(addCommand(null));

        verify(orders).save(any());
    }

    @Test void addLine_allowed_when_supply_secured() {
        // The amendable window extends to the reserved order (inventory reconciles
        // the change incrementally). supply_secured is the reserved checkpoint
        // (renamed from ready_to_ship); the domain anyLineShipped predicate is the
        // backstop once a line actually ships.
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("25.00"), Currencies.AUD, null, 0)
        ));
        when(sagaManager.currentState(ORDER_ID)).thenReturn(Optional.of(SalesOrderFulfilmentSaga.SUPPLY_SECURED));

        service.addLine(addCommand(null));

        verify(orders).save(any());
    }

    @Test void addLine_rejected_for_prepayment_order_at_supply_secured() {
        // Finance guard: prepayment/deposit orders invoice up front, so a
        // supply_secured prepayment order already carries a pre-shipment invoice.
        when(sagaManager.currentState(ORDER_ID)).thenReturn(Optional.of(SalesOrderFulfilmentSaga.SUPPLY_SECURED));
        when(sagaManager.currentPaymentTerms(ORDER_ID)).thenReturn(Optional.of(PaymentTerms.PREPAYMENT.dbValue()));

        assertThatThrownBy(() -> service.addLine(addCommand(null)))
            .isInstanceOf(OrderNotAmendableException.class);

        verify(orders, never()).save(any());
    }

    @Test void addLine_rejected_once_saga_past_the_window() {
        // Past the amendable window — the saga has completed (post-supply states
        // collapsed, so completed is the first non-amendable state reachable on
        // the happy path). The "once goods shipped" guarantee is enforced
        // separately by the domain anyLineShipped predicate (SalesOrder tests).
        // The window guard runs before price resolution, so no productCards stub.
        when(sagaManager.currentState(ORDER_ID))
            .thenReturn(Optional.of(SalesOrderFulfilmentSaga.COMPLETED));

        assertThatThrownBy(() -> service.addLine(addCommand(null)))
            .isInstanceOf(OrderNotAmendableException.class);

        verify(orders, never()).save(any());
    }

    @Test void addLine_rejected_on_stale_version() {
        assertThatThrownBy(() -> service.addLine(addCommand(99L)))
            .isInstanceOf(OrderVersionConflictException.class);

        verify(orders, never()).save(any());
    }

    @Test void removeLine_rejected_when_it_would_empty_the_order() {
        when(sagaManager.currentState(ORDER_ID)).thenReturn(Optional.of(SalesOrderFulfilmentSaga.STARTED));

        assertThatThrownBy(() -> service.removeLine(new RemoveOrderLineCommand(ORDER_ID, LINE_ID, null)))
            .isInstanceOf(EmptyOrderNotAllowedException.class);

        verify(orders, never()).save(any());
    }
}
