package com.northwood.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.ProductCardLookup;
import com.northwood.inventory.application.ProductCardLookup.Replenishment;
import com.northwood.inventory.application.ReorderPolicyLookup;
import com.northwood.inventory.application.ReorderPolicyLookup.ReorderPolicy;
import com.northwood.inventory.application.StockBalanceLookup;
import com.northwood.inventory.application.dto.StockBalanceView;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.ReplenishmentRequest.TargetService;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ReplenishmentDetectionServiceTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Mock ReorderPolicyLookup reorderPolicies;
    @Mock StockBalanceLookup stockBalances;
    @Mock ProductCardLookup productCards;
    @Mock ReplenishmentRequestRepository replenishmentRequests;
    @Mock OutboxAppender outbox;

    private ReplenishmentDetectionService service;

    @BeforeEach
    void setUp() {
        // Real saver over the mocked repository: without a live transaction the
        // NESTED savepoint is a no-op, so it delegates straight to save() — the
        // DuplicateKeyException still propagates to the service's catch, which is
        // what these tests exercise.
        service = new ReplenishmentDetectionService(
            reorderPolicies, stockBalances, productCards, replenishmentRequests,
            new ReplenishmentRequestSaver(replenishmentRequests), outbox
        );
    }

    private void stubPolicy(BigDecimal point, BigDecimal qty) {
        when(reorderPolicies.findByProductId(PRODUCT))
            .thenReturn(Optional.of(new ReorderPolicy(point, qty)));
    }

    private void stubOnHand(BigDecimal onHand) {
        when(stockBalances.findBalance(WAREHOUSE, PRODUCT))
            .thenReturn(Optional.of(new StockBalanceView(
                WAREHOUSE, PRODUCT,
                onHand, BigDecimal.ZERO, onHand
            )));
    }

    private void stubFlags(boolean purchased, boolean manufactured) {
        when(productCards.findByProductId(PRODUCT))
            .thenReturn(Optional.of(new Replenishment(purchased, manufactured)));
    }

    @Test void no_policy_means_no_check() {
        when(reorderPolicies.findByProductId(PRODUCT)).thenReturn(Optional.empty());

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(stockBalances, never()).findBalance(any(), any());
        verify(replenishmentRequests, never()).save(any());
    }

    @Test void zero_reorder_point_means_no_automatic_replenishment() {
        stubPolicy(BigDecimal.ZERO, new BigDecimal("10"));

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(stockBalances, never()).findBalance(any(), any());
        verify(replenishmentRequests, never()).save(any());
    }

    @Test void above_threshold_no_action() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("8"));

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(productCards, never()).findByProductId(any());
        verify(replenishmentRequests, never()).save(any());
    }

    @Test void at_threshold_no_action() {
        // Boundary: on_hand == reorder_point is NOT a breach.
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("5"));

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(replenishmentRequests, never()).save(any());
    }

    @Test void below_threshold_manufactured_routes_to_manufacturing() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("3"));
        stubFlags(false, true);

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        ReplenishmentRequest r = captor.getValue();
        assertThat(r.targetService()).isEqualTo(TargetService.MANUFACTURING);
        assertThat(r.reason()).isEqualTo(Reason.REORDER_POINT_BREACH);
        assertThat(r.requestedQuantity()).isEqualByComparingTo("10");
    }

    @Test void below_threshold_purchased_routes_to_purchasing() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("3"));
        stubFlags(true, false);

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        assertThat(captor.getValue().targetService()).isEqualTo(TargetService.PURCHASING);
    }

    @Test void both_flags_true_defaults_to_manufacturing() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("3"));
        stubFlags(true, true);

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        assertThat(captor.getValue().targetService()).isEqualTo(TargetService.MANUFACTURING);
    }

    @Test void unsourceable_skus_log_and_skip() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("3"));
        stubFlags(false, false);

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(replenishmentRequests, never()).save(any());
    }

    @Test void missing_product_card_row_skips() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("3"));
        when(productCards.findByProductId(PRODUCT)).thenReturn(Optional.empty());

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(replenishmentRequests, never()).save(any());
    }

    @Test void non_positive_reorder_quantity_skips() {
        stubPolicy(new BigDecimal("5"), BigDecimal.ZERO);
        stubOnHand(new BigDecimal("3"));

        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(productCards, never()).findByProductId(any());
        verify(replenishmentRequests, never()).save(any());
    }

    @Test void duplicate_key_on_save_is_swallowed_as_invariant_already_held() {
        stubPolicy(new BigDecimal("5"), new BigDecimal("10"));
        stubOnHand(new BigDecimal("3"));
        stubFlags(false, true);
        doThrow(new DuplicateKeyException("uq_replenishment_request_open"))
            .when(replenishmentRequests).save(any());

        // Must not throw — the partial unique index has already enforced the
        // one-open-per-SKU/warehouse invariant.
        service.checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);

        verify(replenishmentRequests).save(any());
    }

    @Test void sales_order_shortage_sourceable_saves_request_with_back_reference() {
        UUID soHeader = UUID.randomUUID();
        UUID soLine = UUID.randomUUID();
        stubFlags(true, false);

        service.raiseForSalesOrderShortage(PRODUCT, WAREHOUSE, new BigDecimal("4"), soHeader, soLine);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        ReplenishmentRequest r = captor.getValue();
        assertThat(r.reason()).isEqualTo(Reason.SALES_ORDER_SHORTAGE);
        assertThat(r.targetService()).isEqualTo(TargetService.PURCHASING);
        assertThat(r.sourceSalesOrderHeaderId()).isEqualTo(soHeader);
        assertThat(r.sourceSalesOrderLineId()).isEqualTo(soLine);
        verify(outbox, never()).append(any(), any());
    }

    @Test void sales_order_shortage_unsourceable_emits_cancelled_and_raises_nothing() {
        UUID soHeader = UUID.randomUUID();
        UUID soLine = UUID.randomUUID();
        stubFlags(false, false);   // neither purchased nor manufactured

        service.raiseForSalesOrderShortage(PRODUCT, WAREHOUSE, new BigDecimal("4"), soHeader, soLine);

        verify(replenishmentRequests, never()).save(any());
        ArgumentCaptor<ReplenishmentCancelled> cap = ArgumentCaptor.forClass(ReplenishmentCancelled.class);
        verify(outbox).append(cap.capture(), eq(ReplenishmentRequest.AGGREGATE_TYPE));
        ReplenishmentCancelled c = cap.getValue();
        assertThat(c.sourceSalesOrderHeaderId()).isEqualTo(soHeader);
        assertThat(c.sourceSalesOrderLineId()).isEqualTo(soLine);
        assertThat(c.productId()).isEqualTo(PRODUCT);
    }

    @Test void sales_order_shortage_missing_card_emits_cancelled() {
        UUID soHeader = UUID.randomUUID();
        UUID soLine = UUID.randomUUID();
        when(productCards.findByProductId(PRODUCT)).thenReturn(Optional.empty());

        service.raiseForSalesOrderShortage(PRODUCT, WAREHOUSE, new BigDecimal("4"), soHeader, soLine);

        verify(replenishmentRequests, never()).save(any());
        verify(outbox).append(any(ReplenishmentCancelled.class), eq(ReplenishmentRequest.AGGREGATE_TYPE));
    }

    @Test void order_pegged_sourceable_saves_request_with_reason_and_back_reference() {
        UUID soHeader = UUID.randomUUID();
        UUID soLine = UUID.randomUUID();
        stubFlags(false, true);   // manufactured → make-to-order

        service.raiseForOrderPegged(PRODUCT, WAREHOUSE, new BigDecimal("10"), soHeader, soLine);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        ReplenishmentRequest r = captor.getValue();
        assertThat(r.reason()).isEqualTo(Reason.ORDER_PEGGED);
        assertThat(r.targetService()).isEqualTo(TargetService.MANUFACTURING);
        assertThat(r.requestedQuantity()).isEqualByComparingTo("10");
        assertThat(r.sourceSalesOrderHeaderId()).isEqualTo(soHeader);
        assertThat(r.sourceSalesOrderLineId()).isEqualTo(soLine);
        verify(outbox, never()).append(any(), any());
    }

    @Test void order_pegged_unsourceable_emits_cancelled_and_raises_nothing() {
        UUID soHeader = UUID.randomUUID();
        UUID soLine = UUID.randomUUID();
        stubFlags(false, false);   // neither purchased nor manufactured

        service.raiseForOrderPegged(PRODUCT, WAREHOUSE, new BigDecimal("10"), soHeader, soLine);

        verify(replenishmentRequests, never()).save(any());
        verify(outbox).append(any(ReplenishmentCancelled.class), eq(ReplenishmentRequest.AGGREGATE_TYPE));
    }

    @Test void raiseIfNoneOpen_supports_work_order_shortage_path() {
        // The detection bridge will call raiseIfNoneOpen directly with its own
        // quantity + reason; this verifies the path is reusable for both
        // triggers and routes by make-vs-buy the same way.
        stubFlags(true, false);

        service.raiseIfNoneOpen(PRODUCT, WAREHOUSE, new BigDecimal("7"), Reason.WORK_ORDER_SHORTAGE);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        ReplenishmentRequest r = captor.getValue();
        assertThat(r.reason()).isEqualTo(Reason.WORK_ORDER_SHORTAGE);
        assertThat(r.targetService()).isEqualTo(TargetService.PURCHASING);
        assertThat(r.requestedQuantity()).isEqualByComparingTo("7");
    }
}
