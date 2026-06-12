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
import com.northwood.manufacturing.domain.Routing;
import com.northwood.manufacturing.domain.RoutingId;
import com.northwood.manufacturing.domain.RoutingOperation;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.domain.Currencies;
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

@ExtendWith(MockitoExtension.class)
class MaterialsCostRollupServiceTest {

    @Mock ProductReplenishmentProjection replenishment;
    @Mock ProductApprovedVendorProjection approvedVendors;
    @Mock ProductMaterialsCostProjection materialsCosts;
    @Mock BomLookup bomLookup;
    @Mock RoutingQueryPort routings;
    @Mock WorkCenterRateLookup workCenterRates;
    @Mock OutboxAppender outbox;

    private MaterialsCostRollupService rollup;

    @BeforeEach
    void setUp() {
        rollup = new MaterialsCostRollupService(
            replenishment, approvedVendors, materialsCosts, bomLookup,
            new ConversionCostCalculator(routings, workCenterRates), outbox
        );
    }

    @Nested
    class SupplierPricePath {

        @Test
        void purchasedItem_preferredMatch_appliesAndEmits() {
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(product)).thenReturn(Optional.of(supplier));
            when(materialsCosts.findByProductId(product)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(product)).thenReturn(List.of());

            rollup.onSupplierPriceChange(supplier, product, Currencies.AUD, new BigDecimal("12.50"));

            verify(materialsCosts).apply(
                eq(product), eq(new BigDecimal("12.50")), eq(Currencies.AUD),
                eq("supplier_price_change"), any(Instant.class)
            );
            ArgumentCaptor<ProductMaterialsCostComputed> eventCaptor =
                ArgumentCaptor.forClass(ProductMaterialsCostComputed.class);
            verify(outbox).append(eventCaptor.capture(), eq(ProductMaterialsCostComputed.AGGREGATE_TYPE));
            assertThat(eventCaptor.getValue().eventType())
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

            rollup.onSupplierPriceChange(supplierB, product, Currencies.AUD, new BigDecimal("9.99"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).append(any(), any());
        }

        @Test
        void ambiguousPreferredVendor_emitsInputsMissing() {
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(product)).thenReturn(Optional.empty());
            when(materialsCosts.findByProductId(product)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(product)).thenReturn(List.of());

            rollup.onSupplierPriceChange(supplier, product, Currencies.AUD, new BigDecimal("12.50"));

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

            rollup.onSupplierPriceChange(supplier, product, Currencies.AUD, new BigDecimal("12.50"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).append(any(), any());
        }

        @Test
        void manufacturedOnly_noBom_skipsSilently() {
            UUID supplier = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(product)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(product))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(false, true)));

            rollup.onSupplierPriceChange(supplier, product, Currencies.AUD, new BigDecimal("12.50"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).append(any(), any());
        }
    }

    @Nested
    class BomRollup {

        @Test
        void singleLevel_sumsComponents() {
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
                    componentA, new BigDecimal("5.00"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(componentB)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentB, new BigDecimal("4.00"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            rollup.recomputeViaBom(parent, "bom_activated");

            // 2 * 5 + 3 * 4 = 22.00
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("22.000000")), eq(Currencies.AUD),
                eq("bom_activated"), any(Instant.class)
            );
        }

        @Test
        void scrapFactor_uplift_appliedPerLine() {
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("2"), new BigDecimal("10"), Bom.ComponentKind.RAW) // 10% scrap
                ))));
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, new BigDecimal("5.00"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            rollup.recomputeViaBom(parent, "bom_activated");

            // 2 * 1.1 * 5 = 11.00
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("11.000000")), eq(Currencies.AUD),
                eq("bom_activated"), any(Instant.class)
            );
        }

        @Test
        void componentMissingCost_propagatesInputsMissing() {
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
                    componentA, new BigDecimal("5.00"), Currencies.AUD,
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
                    componentA, new BigDecimal("5.00"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(componentB)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentB, new BigDecimal("4.00"), Currencies.USD,
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
            verify(outbox, never()).append(any(), any());
        }

        @Test
        void ownRoutingConversion_addedToStandardCostOnEvent() {
            UUID parent = UUID.randomUUID();
            UUID componentA = UUID.randomUUID();
            UUID workCenter = UUID.randomUUID();
            when(bomLookup.findActiveByFinishedProductId(parent))
                .thenReturn(Optional.of(new BomLookup.ActiveBom(UUID.randomUUID(), List.of(
                    new BomLookup.Component(componentA, "RM-A", "Component A",
                        new BigDecimal("1"), BigDecimal.ZERO, Bom.ComponentKind.RAW)
                ))));
            when(materialsCosts.findByProductId(componentA)).thenReturn(Optional.of(
                new ProductMaterialsCostProjection.MaterialsCost(
                    componentA, new BigDecimal("10.00"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                )));
            when(materialsCosts.findByProductId(parent)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(parent)).thenReturn(List.of());

            // One 10-minute operation (0 setup + 10 run) at a work centre billing
            // 0.50 labour + 0.25 overhead per minute → conversion 10 * 0.75 = 7.50.
            when(routings.findActiveByFinishedProductId(parent)).thenReturn(Optional.of(
                new Routing(RoutingId.of(UUID.randomUUID()), parent, "1", Routing.ACTIVE, List.of(
                    new RoutingOperation(UUID.randomUUID(), 10, "OP-10", "Assemble",
                        workCenter, BigDecimal.ZERO, new BigDecimal("10"))
                ))));
            when(workCenterRates.findByWorkCenterId(workCenter)).thenReturn(Optional.of(
                new WorkCenterRateLookup.Rates(new BigDecimal("0.50"), new BigDecimal("0.25"))));

            rollup.recomputeViaBom(parent, "bom_activated");

            // materials_cost facet stays material-only (1 * 10 = 10).
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("10.000000")), eq(Currencies.AUD),
                eq("bom_activated"), any(Instant.class)
            );
            // emitted standardCost = material 10 + conversion 7.5 = 17.5.
            ArgumentCaptor<ProductMaterialsCostComputed> captor =
                ArgumentCaptor.forClass(ProductMaterialsCostComputed.class);
            verify(outbox).append(captor.capture(), eq(ProductMaterialsCostComputed.AGGREGATE_TYPE));
            assertThat(captor.getValue().materialsCost()).isEqualByComparingTo("10.00");
            assertThat(captor.getValue().standardCost()).isEqualByComparingTo("17.50");
        }
    }

    @Nested
    class ParentWalk {

        @Test
        void supplierPriceChange_walksParent() {
            UUID supplier = UUID.randomUUID();
            UUID rawMaterial = UUID.randomUUID();
            UUID parent = UUID.randomUUID();

            // Supplier-price path for the raw material.
            when(bomLookup.findActiveByFinishedProductId(rawMaterial)).thenReturn(Optional.empty());
            when(replenishment.findByProductId(rawMaterial))
                .thenReturn(Optional.of(new ProductReplenishmentProjection.Replenishment(true, false)));
            when(approvedVendors.findPreferredSupplierId(rawMaterial)).thenReturn(Optional.of(supplier));
            when(materialsCosts.findByProductId(rawMaterial)).thenReturn(Optional.empty());
            when(bomLookup.findParentProductIdsByComponent(rawMaterial)).thenReturn(List.of(parent));

            // BoM rollup for the parent.
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
                    rawMaterial, new BigDecimal("12.50"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                ))); // subsequent call (parent's BoM walk)

            rollup.onSupplierPriceChange(supplier, rawMaterial, Currencies.AUD, new BigDecimal("12.50"));

            // Raw material's apply
            verify(materialsCosts).apply(
                eq(rawMaterial), eq(new BigDecimal("12.50")), eq(Currencies.AUD),
                eq("supplier_price_change"), any(Instant.class)
            );
            // Parent's apply: 2 * 12.50 = 25.00, reason child_materials_cost_changed
            verify(materialsCosts).apply(
                eq(parent), eq(new BigDecimal("25.000000")), eq(Currencies.AUD),
                eq("child_materials_cost_changed"), any(Instant.class)
            );
            verify(outbox, times(2)).append(any(), any());
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
                    product, new BigDecimal("12.50"), Currencies.AUD,
                    "supplier_price_change", Instant.now()
                )));

            rollup.onSupplierPriceChange(supplier, product, Currencies.AUD, new BigDecimal("12.50"));

            verify(materialsCosts, never()).apply(any(), any(), anyString(), anyString(), any());
            verify(outbox, never()).append(any(), any());
            verify(bomLookup, never()).findParentProductIdsByComponent(product);
        }
    }
}
