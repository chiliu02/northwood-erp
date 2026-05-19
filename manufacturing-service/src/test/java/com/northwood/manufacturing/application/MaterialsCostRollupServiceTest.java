package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.events.ProductMaterialsCostComputed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.inbox.ProductApprovedVendorProjection;
import com.northwood.manufacturing.application.inbox.ProductMaterialsCostProjection;
import com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection;
import com.northwood.manufacturing.application.BomLookup;
import com.northwood.manufacturing.domain.Bom;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MaterialsCostRollupServiceTest {

    @Mock ProductReplenishmentProjection replenishment;
    @Mock ProductApprovedVendorProjection approvedVendors;
    @Mock ProductMaterialsCostProjection materialsCosts;
    @Mock BomLookup bomLookup;
    @Mock OutboxPort outbox;
    @Mock CurrentUserAccessor currentUser;

    private final ObjectMapper json = new ObjectMapper();
    private MaterialsCostRollupService rollup;

    @BeforeEach
    void setUp() {
        rollup = new MaterialsCostRollupService(
            replenishment, approvedVendors, materialsCosts, bomLookup, outbox, json, currentUser
        );
    }

    /** Stub {@code currentUsername()} in tests that actually emit (apply + outbox). */
    private void stubCurrentUser() {
        when(currentUser.currentUsername()).thenReturn(Optional.empty());
    }

    @Nested
    class SupplierPricePath {

        @Test
        void purchasedItem_preferredMatch_appliesAndEmits() {
            stubCurrentUser();
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(product)).thenReturn(Optional.of(supplier));
            when(materialsCosts.findByProductId(product)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(product)).thenReturn(List.of());

            rollup.onSupplierPriceChange(supplier, product, "AUD", new BigDecimal("12.50"));

            verify(materialsCosts).apply(
                eq(product), eq(new BigDecimal("12.50")), eq("AUD"),
                eq("supplier_price_change"), any(Instant.class)
            );
            ArgumentCaptor<OutboxRow> rowCaptor = ArgumentCaptor.forClass(OutboxRow.class);
            verify(outbox).appendPending(rowCaptor.capture());
            assertThat(rowCaptor.getValue().getEventType())
                .isEqualTo(ProductMaterialsCostComputed.EVENT_TYPE);
        }

        @Test
        void purchasedItem_nonPreferredSupplier_skips() {
            UUID supplierA = UUID.randomUUID();
            UUID supplierB = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(product)).thenReturn(Optional.of(supplierA));

            rollup.onSupplierPriceChange(supplierB, product, "AUD", new BigDecimal("9.99"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).appendPending(any());
        }

        @Test
        void ambiguousPreferredVendor_emitsInputsMissing() {
            stubCurrentUser();
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(product)).thenReturn(Optional.empty());
            when(materialsCosts.findByProductId(product)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(product)).thenReturn(List.of());

            rollup.onSupplierPriceChange(supplier, product, "AUD", new BigDecimal("12.50"));

            verify(materialsCosts).apply(
                eq(product), eq((BigDecimal) null), eq((String) null),
                eq("inputs_missing"), any(Instant.class)
            );
        }

        @Test
        void productHasActiveBom_supplierEventIgnored() {
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            UUID bom = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(bom, List.of())));

            rollup.onSupplierPriceChange(supplier, product, "AUD", new BigDecimal("12.50"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).appendPending(any());
        }

        @Test
        void manufacturedOnly_noBom_skipsSilently() {
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(false, true)));

            rollup.onSupplierPriceChange(supplier, product, "AUD", new BigDecimal("12.50"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).appendPending(any());
        }
    }

    @Nested
    class BomRollup {

        @Test
        void singleLevel_sumsComponents() {
            stubCurrentUser();
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            UUID componentB = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("2"), BigDecimal.ZERO, Bom.ComponentKind.RAW),
                    new BomLookup.Component(componentB, "RM-B", "Component B",
                        new BigDecimal("3"), BigDecimal.ZERO, Bom.ComponentKind.RAW)
                ))));
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, new BigDecimal("5.00"), "AUD",
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(componentB)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentB, new BigDecimal("4.00"), "AUD",
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            rollup.recomputeViaBom(parent, "bom_activated");

            // 2 * 5 + 3 * 4 = 22.00
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("22.000000")), eq("AUD"),
                eq("bom_activated"), any(Instant.class)
            );
        }

        @Test
        void scrapFactor_uplift_appliedPerLine() {
            stubCurrentUser();
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("2"), new BigDecimal("10"), Bom.ComponentKind.RAW) // 10% scrap
                ))));
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, new BigDecimal("5.00"), "AUD",
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            rollup.recomputeViaBom(parent, "bom_activated");

            // 2 * 1.1 * 5 = 11.00
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("11.000000")), eq("AUD"),
                eq("bom_activated"), any(Instant.class)
            );
        }

        @Test
        void componentMissingCost_propagatesInputsMissing() {
            stubCurrentUser();
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            UUID componentB = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("2"), BigDecimal.ZERO, Bom.ComponentKind.RAW),
                    new BomLookup.Component(componentB, "RM-B", "Component B",
                        new BigDecimal("3"), BigDecimal.ZERO, Bom.ComponentKind.RAW)
                ))));
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, new BigDecimal("5.00"), "AUD",
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(componentB)).thenReturn(Optional.empty()); // missing
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            rollup.recomputeViaBom(parent, "bom_activated");

            verify(materialsCosts).apply(
                eq(parent), eq((BigDecimal) null), eq((String) null),
                eq("inputs_missing"), any(Instant.class)
            );
        }

        @Test
        void componentInputsMissingCost_alsoPropagates() {
            stubCurrentUser();
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("2"), BigDecimal.ZERO, Bom.ComponentKind.RAW)
                ))));
            // Component has a row but materialsCost is null (inputs_missing).
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, null, null, "inputs_missing", Instant.now()
                )));
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            rollup.recomputeViaBom(parent, "bom_activated");

            verify(materialsCosts).apply(
                eq(parent), eq((BigDecimal) null), eq((String) null),
                eq("inputs_missing"), any(Instant.class)
            );
        }

        @Test
        void crossCurrencyComponents_throw() {
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            UUID componentB = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("1"), BigDecimal.ZERO, Bom.ComponentKind.RAW),
                    new BomLookup.Component(componentB, "RM-B", "Component B",
                        new BigDecimal("1"), BigDecimal.ZERO, Bom.ComponentKind.RAW)
                ))));
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, new BigDecimal("5.00"), "AUD",
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(componentB)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentB, new BigDecimal("4.00"), "USD",
                    "supplier_price_change", Instant.now()
                )));

            assertThatThrownBy(() -> rollup.recomputeViaBom(parent, "bom_activated"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currency mismatch");

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
        }

        @Test
        void noActiveBom_isNoOp_doesNotBlowAwayExistingCost() {
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());

            rollup.recomputeViaBom(product, "bom_activated");

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).appendPending(any());
        }
    }

    @Nested
    class ParentWalk {

        @Test
        void supplierPriceChange_walksParent() {
            stubCurrentUser();
            UUID supplier = UUID.randomUUID();
            UUID rawMaterial = UUID.randomUUID();
            UUID parent = UUID.randomUUID();

            // Slice C path for the raw material.
            when(bomLookup.findActiveByFinishedProductId(rawMaterial)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(rawMaterial))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(rawMaterial)).thenReturn(Optional.of(supplier));
            when(materialsCosts.findByProductId(rawMaterial)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(rawMaterial)).thenReturn(List.of(parent));

            // Slice D BoM rollup for the parent.
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(rawMaterial, "RM", "Raw",
                        new BigDecimal("2"), BigDecimal.ZERO, Bom.ComponentKind.RAW)
                ))));
            // After raw material's apply, its projection now returns the new cost.
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            // Stub the lookup chain: the rollup writes raw material first, then walks parent.
            // We need findByProductId(rawMaterial) to return the new cost when computing parent.
            // Mockito returns the latest stub result; reconfigure via OngoingStubbing chain.
            when(materialsCosts.findByProductId(rawMaterial))
                .thenReturn(Optional.empty()) // first call (no-op suppression check on apply)
                .thenReturn(Optional.of(new ProductMaterialsCostProjection.MaterialsCost(
                    rawMaterial, new BigDecimal("12.50"), "AUD",
                    "supplier_price_change", Instant.now()
                ))); // subsequent call (parent's BoM walk)

            rollup.onSupplierPriceChange(supplier, rawMaterial, "AUD", new BigDecimal("12.50"));

            // Raw material's apply
            verify(materialsCosts).apply(
                eq(rawMaterial), eq(new BigDecimal("12.50")), eq("AUD"),
                eq("supplier_price_change"), any(Instant.class)
            );
            // Parent's apply: 2 * 12.50 = 25.00, reason child_materials_cost_changed
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("25.000000")), eq("AUD"),
                eq("child_materials_cost_changed"), any(Instant.class)
            );
            verify(outbox, times(2)).appendPending(any());
        }
    }

    @Nested
    class NoOpSuppression {

        @Test
        void unchangedCost_skipsApplyAndEmit() {
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(product)).thenReturn(Optional.of(supplier));
            when(materialsCosts.findByProductId(product)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    product, new BigDecimal("12.50"), "AUD",
                    "supplier_price_change", Instant.now()
                )));

            rollup.onSupplierPriceChange(supplier, product, "AUD", new BigDecimal("12.50"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).appendPending(any());
            verify(bomLookup, never()).findParentProductIdsByComponent(product);
        }
    }
}
