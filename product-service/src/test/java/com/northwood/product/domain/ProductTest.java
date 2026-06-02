package com.northwood.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.product.domain.events.ApprovedVendorListChanged;
import com.northwood.product.domain.events.ActiveBomChanged;
import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.product.domain.events.ProductCreated;
import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.product.domain.events.ReorderPolicyChanged;
import com.northwood.product.domain.events.ReplenishmentStrategyChanged;
import com.northwood.product.domain.events.SalesPriceChanged;
import com.northwood.product.domain.events.StandardCostChanged;
import com.northwood.product.domain.events.ValuationClassChanged;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {

    private static final UUID UOM_EACH = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Product newProduct() {
        return Product.register(
            new Sku("FG-TEST-001"),
            "Test Widget",
            "Description",
            ProductType.FINISHED_GOOD,
            UOM_EACH,
            Money.of(new BigDecimal("100.00"), Currencies.AUD),
            Money.of(new BigDecimal("60.00"), Currencies.AUD)
        );
    }

    @Nested
    class Register {
        @Test void emits_ProductCreated_with_aggregate_identity() {
            Product p = newProduct();
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ProductCreated.class);
            ProductCreated e = (ProductCreated) events.get(0);
            assertThat(e.aggregateId()).isEqualTo(p.id().value());
            assertThat(e.sku()).isEqualTo(p.sku().value());
            assertThat(e.productType()).isEqualTo(p.productType().dbValue());
        }

        @Test void starts_active() {
            assertThat(newProduct().status()).isEqualTo(Product.Status.ACTIVE);
        }

        @Test void starts_with_zero_reorder_policy() {
            Product p = newProduct();
            assertThat(p.reorderPoint()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(p.reorderQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test void starts_with_null_shape_a_facets() {
            Product p = newProduct();
            assertThat(p.valuationClass()).isNull();
            assertThat(p.activeBomId()).isNull();
        }

        @Test void starts_with_default_make_vs_buy_flags_false() {
            Product p = newProduct();
            assertThat(p.isStocked()).isFalse();
            assertThat(p.isPurchased()).isFalse();
            assertThat(p.isManufactured()).isFalse();
            assertThat(p.isSellable()).isFalse();
        }

        @Test void starts_to_stock_for_non_service() {
            assertThat(newProduct().replenishmentStrategy()).isEqualTo(ReplenishmentStrategy.TO_STOCK);
        }

        @Test void starts_with_null_strategy_for_service() {
            Product p = Product.register(
                new Sku("SVC-001"), "Install service", "d", ProductType.SERVICE, UOM_EACH,
                Money.zero(Currencies.AUD), Money.zero(Currencies.AUD)
            );
            assertThat(p.replenishmentStrategy()).isNull();
        }

        @Test void rejects_null_sku() {
            assertThatThrownBy(() -> Product.register(
                null, "n", "d", ProductType.FINISHED_GOOD, UOM_EACH,
                Money.zero(Currencies.AUD), Money.zero(Currencies.AUD)
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        void rejects_blank_name(String badName) {
            assertThatThrownBy(() -> Product.register(
                new Sku("FG-X"), badName, "d", ProductType.FINISHED_GOOD, UOM_EACH,
                Money.zero(Currencies.AUD), Money.zero(Currencies.AUD)
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_product_type() {
            assertThatThrownBy(() -> Product.register(
                new Sku("FG-X"), "n", "d", null, UOM_EACH,
                Money.zero(Currencies.AUD), Money.zero(Currencies.AUD)
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_base_uom_id() {
            assertThatThrownBy(() -> Product.register(
                new Sku("FG-X"), "n", "d", ProductType.FINISHED_GOOD, null,
                Money.zero(Currencies.AUD), Money.zero(Currencies.AUD)
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_sales_price() {
            assertThatThrownBy(() -> Product.register(
                new Sku("FG-X"), "n", "d", ProductType.FINISHED_GOOD, UOM_EACH,
                null, Money.zero(Currencies.AUD)
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_standard_cost() {
            assertThatThrownBy(() -> Product.register(
                new Sku("FG-X"), "n", "d", ProductType.FINISHED_GOOD, UOM_EACH,
                Money.zero(Currencies.AUD), null
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ChangeSalesPrice {
        @Test void updates_price_and_emits_event() {
            Product p = newProduct();
            p.pullPendingEvents();   // drain ProductCreated
            p.changeSalesPrice(Money.of(new BigDecimal("150.00"), Currencies.AUD));
            assertThat(p.salesPrice().amount()).isEqualByComparingTo(new BigDecimal("150.00"));
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SalesPriceChanged.class);
            SalesPriceChanged e = (SalesPriceChanged) events.get(0);
            assertThat(e.oldSalesPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(e.newSalesPrice()).isEqualByComparingTo(new BigDecimal("150.00"));
            assertThat(e.currencyCode()).isEqualTo(Currencies.AUD);
        }

        @Test void leaves_standard_cost_unchanged() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeSalesPrice(Money.of(new BigDecimal("150.00"), Currencies.AUD));
            assertThat(p.standardCost().amount()).isEqualByComparingTo(new BigDecimal("60.00"));
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeSalesPrice(Money.zero(Currencies.AUD)))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void rejects_null() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeSalesPrice(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void no_op_emits_nothing_when_value_matches() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeSalesPrice(Money.of(new BigDecimal("100.00"), Currencies.AUD));
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void no_op_ignores_BigDecimal_scale() {
            // current sales price is 100.00 AUD
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeSalesPrice(Money.of(new BigDecimal("100"), Currencies.AUD));   // scale=0 vs scale=2
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void rejection_runs_before_no_op_check() {
            // Discontinued + value-matches: still throws.
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeSalesPrice(Money.of(new BigDecimal("100.00"), Currencies.AUD)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ChangeStandardCost {
        @Test void updates_cost_and_emits_event() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeStandardCost(Money.of(new BigDecimal("80.00"), Currencies.AUD));
            assertThat(p.standardCost().amount()).isEqualByComparingTo(new BigDecimal("80.00"));
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(StandardCostChanged.class);
            StandardCostChanged e = (StandardCostChanged) events.get(0);
            assertThat(e.oldStandardCost()).isEqualByComparingTo(new BigDecimal("60.00"));
            assertThat(e.newStandardCost()).isEqualByComparingTo(new BigDecimal("80.00"));
            assertThat(e.currencyCode()).isEqualTo(Currencies.AUD);
        }

        @Test void leaves_sales_price_unchanged() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeStandardCost(Money.of(new BigDecimal("80.00"), Currencies.AUD));
            assertThat(p.salesPrice().amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeStandardCost(Money.zero(Currencies.AUD)))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void rejects_null() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeStandardCost(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void no_op_emits_nothing_when_value_matches() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeStandardCost(Money.of(new BigDecimal("60.00"), Currencies.AUD));
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void no_op_ignores_BigDecimal_scale() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeStandardCost(Money.of(new BigDecimal("60"), Currencies.AUD));   // scale=0 vs scale=2
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void rejection_runs_before_no_op_check() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeStandardCost(Money.of(new BigDecimal("60.00"), Currencies.AUD)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ChangeMakeVsBuy {
        @Test void requires_at_least_one_flag_true() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeMakeVsBuy(false, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void allows_purchased_only() {
            Product p = newProduct();
            p.changeMakeVsBuy(true, false);
            assertThat(p.isPurchased()).isTrue();
            assertThat(p.isManufactured()).isFalse();
        }

        @Test void allows_manufactured_only() {
            Product p = newProduct();
            p.changeMakeVsBuy(false, true);
            assertThat(p.isPurchased()).isFalse();
            assertThat(p.isManufactured()).isTrue();
        }

        @Test void allows_both_true_for_vertically_integrated() {
            Product p = newProduct();
            p.changeMakeVsBuy(true, true);
            assertThat(p.isPurchased()).isTrue();
            assertThat(p.isManufactured()).isTrue();
        }

        @Test void emits_event_with_old_and_new() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeMakeVsBuy(true, false);
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(1);
            MakeVsBuyChanged e = (MakeVsBuyChanged) events.get(0);
            assertThat(e.oldIsPurchased()).isFalse();
            assertThat(e.newIsPurchased()).isTrue();
            assertThat(e.oldIsManufactured()).isFalse();
            assertThat(e.newIsManufactured()).isFalse();
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeMakeVsBuy(true, false))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_emits_nothing_when_flags_match() {
            Product p = newProduct();
            p.changeMakeVsBuy(true, false);
            p.pullPendingEvents();
            p.changeMakeVsBuy(true, false);
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void invariant_runs_before_no_op_check() {
            // (false, false) is invalid even when current state is (false, false).
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeMakeVsBuy(false, false))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ChangeReorderPolicy {
        @Test void rejects_negative_reorder_point() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeReorderPolicy(new BigDecimal("-1"), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_negative_reorder_quantity() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeReorderPolicy(BigDecimal.TEN, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void allows_zero_thresholds() {
            Product p = newProduct();
            p.changeReorderPolicy(BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(p.reorderPoint()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test void emits_event_with_old_and_new() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeReorderPolicy(new BigDecimal("5"), new BigDecimal("20"));
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(1);
            ReorderPolicyChanged e = (ReorderPolicyChanged) events.get(0);
            assertThat(e.newReorderPoint()).isEqualByComparingTo(new BigDecimal("5"));
            assertThat(e.newReorderQuantity()).isEqualByComparingTo(new BigDecimal("20"));
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeReorderPolicy(BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_emits_nothing_when_values_match() {
            Product p = newProduct();
            p.changeReorderPolicy(new BigDecimal("5"), new BigDecimal("20"));
            p.pullPendingEvents();
            p.changeReorderPolicy(new BigDecimal("5"), new BigDecimal("20"));
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void no_op_ignores_BigDecimal_scale() {
            Product p = newProduct();
            p.changeReorderPolicy(new BigDecimal("5"), new BigDecimal("20"));
            p.pullPendingEvents();
            p.changeReorderPolicy(new BigDecimal("5.00"), new BigDecimal("20.0"));
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void invariant_runs_before_no_op_check() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeReorderPolicy(new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ChangeReplenishmentStrategy {

        /** Sellable, zero-reorder finished good — eligible to flip to to_order. */
        private static Product sellableToStock() {
            return Product.reconstitute(
                ProductId.newId(), new Sku("FG-SELL-001"), "Sellable FG", null,
                ProductType.FINISHED_GOOD, UOM_EACH,
                true, false, true, /* sellable */ true,
                Money.of(new BigDecimal("100"), Currencies.AUD), Money.of(new BigDecimal("60"), Currencies.AUD),
                BigDecimal.ZERO, BigDecimal.ZERO,
                ReplenishmentStrategy.TO_STOCK, null, null,
                Product.Status.ACTIVE, 1L,
                List.of()
            );
        }

        @Test void flips_to_order_and_emits_event() {
            Product p = sellableToStock();
            p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_ORDER);
            assertThat(p.replenishmentStrategy()).isEqualTo(ReplenishmentStrategy.TO_ORDER);
            ReplenishmentStrategyChanged e = (ReplenishmentStrategyChanged) p.pullPendingEvents().get(0);
            assertThat(e.oldReplenishmentStrategy()).isEqualTo("to_stock");
            assertThat(e.newReplenishmentStrategy()).isEqualTo("to_order");
        }

        @Test void to_order_rejected_when_not_sellable() {
            Product p = newProduct();   // sellable=false
            assertThatThrownBy(() -> p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_ORDER))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void to_order_rejected_when_reorder_nonzero() {
            Product p = Product.reconstitute(
                ProductId.newId(), new Sku("FG-RO-001"), "FG", null,
                ProductType.FINISHED_GOOD, UOM_EACH,
                true, false, true, true,
                Money.of(new BigDecimal("100"), Currencies.AUD), Money.of(new BigDecimal("60"), Currencies.AUD),
                new BigDecimal("5"), new BigDecimal("20"),
                ReplenishmentStrategy.TO_STOCK, null, null,
                Product.Status.ACTIVE, 1L, List.of()
            );
            assertThatThrownBy(() -> p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_ORDER))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void service_product_rejects_non_null_strategy() {
            Product svc = Product.register(
                new Sku("SVC-001"), "Service", "d", ProductType.SERVICE, UOM_EACH,
                Money.zero(Currencies.AUD), Money.zero(Currencies.AUD)
            );
            assertThatThrownBy(() -> svc.changeReplenishmentStrategy(ReplenishmentStrategy.TO_STOCK))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_when_discontinued() {
            Product p = sellableToStock();
            p.discontinue();
            assertThatThrownBy(() -> p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_ORDER))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_emits_nothing_when_unchanged() {
            Product p = sellableToStock();
            p.pullPendingEvents();
            p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_STOCK);
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void reorder_policy_rejects_nonzero_on_to_order_product() {
            Product p = sellableToStock();
            p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_ORDER);
            p.pullPendingEvents();
            assertThatThrownBy(() -> p.changeReorderPolicy(new BigDecimal("5"), new BigDecimal("20")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void reorder_policy_allows_zero_on_to_order_product() {
            Product p = sellableToStock();
            p.changeReplenishmentStrategy(ReplenishmentStrategy.TO_ORDER);
            p.pullPendingEvents();
            p.changeReorderPolicy(BigDecimal.ZERO, BigDecimal.ZERO);   // no-op, but must not throw
            assertThat(p.reorderPoint()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    class ChangeValuationClass {
        @Test void rejects_null_class() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.changeValuationClass(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void emits_event_with_old_null_on_first_set() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.changeValuationClass(ValuationClass.FINISHED_GOODS);
            ValuationClassChanged e = (ValuationClassChanged) p.pullPendingEvents().get(0);
            assertThat(e.oldValuationClass()).isNull();
            assertThat(e.newValuationClass()).isEqualTo("finished_goods");
        }

        @Test void carries_old_value_on_subsequent_change() {
            Product p = newProduct();
            p.changeValuationClass(ValuationClass.FINISHED_GOODS);
            p.pullPendingEvents();
            p.changeValuationClass(ValuationClass.RAW_MATERIALS);
            ValuationClassChanged e = (ValuationClassChanged) p.pullPendingEvents().get(0);
            assertThat(e.oldValuationClass()).isEqualTo("finished_goods");
            assertThat(e.newValuationClass()).isEqualTo("raw_materials");
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.changeValuationClass(ValuationClass.FINISHED_GOODS))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_emits_nothing_when_class_matches() {
            Product p = newProduct();
            p.changeValuationClass(ValuationClass.FINISHED_GOODS);
            p.pullPendingEvents();
            p.changeValuationClass(ValuationClass.FINISHED_GOODS);
            assertThat(p.pullPendingEvents()).isEmpty();
        }
    }

    @Nested
    class ActivateBom {
        @Test void allows_null_to_clear() {
            Product p = newProduct();
            UUID bomId = UUID.randomUUID();
            p.activateBom(bomId);
            p.pullPendingEvents();
            p.activateBom(null);
            assertThat(p.activeBomId()).isNull();
            ActiveBomChanged e = (ActiveBomChanged) p.pullPendingEvents().get(0);
            assertThat(e.oldBomHeaderId()).isEqualTo(bomId);
            assertThat(e.newBomHeaderId()).isNull();
        }

        @Test void emits_event_with_old_null_on_first_set() {
            Product p = newProduct();
            p.pullPendingEvents();
            UUID bomId = UUID.randomUUID();
            p.activateBom(bomId);
            ActiveBomChanged e = (ActiveBomChanged) p.pullPendingEvents().get(0);
            assertThat(e.oldBomHeaderId()).isNull();
            assertThat(e.newBomHeaderId()).isEqualTo(bomId);
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.activateBom(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_emits_nothing_when_id_matches() {
            Product p = newProduct();
            UUID bomId = UUID.randomUUID();
            p.activateBom(bomId);
            p.pullPendingEvents();
            p.activateBom(bomId);
            assertThat(p.pullPendingEvents()).isEmpty();
        }

        @Test void no_op_emits_nothing_when_both_null() {
            // Initial state is null; setting to null again is a no-op.
            Product p = newProduct();
            p.pullPendingEvents();
            p.activateBom(null);
            assertThat(p.pullPendingEvents()).isEmpty();
        }
    }

    @Nested
    class ApprovedVendorList {
        @Test void sets_list_marks_dirty_and_emits_event() {
            Product p = newProduct();
            p.pullPendingEvents();
            UUID supA = UUID.randomUUID();
            UUID supB = UUID.randomUUID();
            p.setApprovedVendors(List.of(
                new ApprovedVendor(supA, "S-A", "Supplier A", true),
                new ApprovedVendor(supB, "S-B", "Supplier B", false)
            ));
            assertThat(p.approvedVendors()).hasSize(2);
            assertThat(p.pullApprovedVendorsDirty()).isTrue();
            ApprovedVendorListChanged e = (ApprovedVendorListChanged) p.pullPendingEvents().get(0);
            assertThat(e.approvedVendors()).hasSize(2);
            assertThat(e.approvedVendors().get(0).preferred()).isTrue();
            assertThat(e.approvedVendors().get(1).preferred()).isFalse();
        }

        @Test void no_op_on_same_set_emits_nothing_and_leaves_dirty_false() {
            UUID supA = UUID.randomUUID();
            ApprovedVendor existing = new ApprovedVendor(supA, "S-A", "Supplier A", true);
            Product p = Product.reconstitute(
                ProductId.newId(), new Sku("FG-X-001"), "P", null,
                ProductType.FINISHED_GOOD, UOM_EACH,
                false, false, false, false,
                Money.of(new BigDecimal("10"), Currencies.AUD), Money.of(new BigDecimal("5"), Currencies.AUD),
                BigDecimal.ZERO, BigDecimal.ZERO,
                ReplenishmentStrategy.TO_STOCK, null, null,
                Product.Status.ACTIVE, 1L,
                List.of(existing)
            );
            p.setApprovedVendors(List.of(new ApprovedVendor(supA, "S-A", "Supplier A", true)));
            assertThat(p.pullPendingEvents()).isEmpty();
            assertThat(p.pullApprovedVendorsDirty()).isFalse();
        }

        @Test void pull_dirty_resets_to_false() {
            Product p = newProduct();
            p.setApprovedVendors(List.of(
                new ApprovedVendor(UUID.randomUUID(), "S-A", "Supplier A", true)
            ));
            assertThat(p.pullApprovedVendorsDirty()).isTrue();
            assertThat(p.pullApprovedVendorsDirty()).isFalse();
        }

        @Test void rejects_null_list() {
            Product p = newProduct();
            assertThatThrownBy(() -> p.setApprovedVendors(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_when_discontinued() {
            Product p = newProduct();
            p.discontinue();
            assertThatThrownBy(() -> p.setApprovedVendors(List.of()))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class Discontinue {
        @Test void flips_status_and_emits_event() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.discontinue();
            assertThat(p.status()).isEqualTo(Product.Status.DISCONTINUED);
            assertThat(p.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(ProductDiscontinued.class);
        }

        @Test void is_idempotent_no_double_event() {
            Product p = newProduct();
            p.pullPendingEvents();
            p.discontinue();
            p.pullPendingEvents();   // drain first ProductDiscontinued
            p.discontinue();         // second call should no-op
            assertThat(p.pullPendingEvents()).isEmpty();
        }
    }

    @Nested
    class Reconstitute {
        @Test void hydrates_without_events() {
            UUID id = UUID.randomUUID();
            Product p = Product.reconstitute(
                ProductId.of(id),
                new Sku("FG-X-001"),
                "Hydrated", "desc",
                ProductType.FINISHED_GOOD,
                UOM_EACH,
                false, false, false, false,
                Money.of(new BigDecimal("10"), Currencies.AUD),
                Money.of(new BigDecimal("5"), Currencies.AUD),
                BigDecimal.ZERO, BigDecimal.ZERO,
                ReplenishmentStrategy.TO_STOCK, ValuationClass.RAW_MATERIALS, null,
                Product.Status.ACTIVE, 5L,
                List.of()
            );
            assertThat(p.pullPendingEvents()).isEmpty();
            assertThat(p.id().value()).isEqualTo(id);
            assertThat(p.version()).isEqualTo(5L);
            assertThat(p.valuationClass()).isEqualTo(ValuationClass.RAW_MATERIALS);
        }
    }

    @Nested
    class PullPendingEvents {
        @Test void drains_after_pulling() {
            Product p = newProduct();
            assertThat(p.pullPendingEvents()).hasSize(1);
            assertThat(p.pullPendingEvents()).isEmpty();
        }
    }
}
