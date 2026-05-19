package com.northwood.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.product.application.dto.ApprovedVendorCommand;
import com.northwood.product.application.dto.ProductView;
import com.northwood.product.domain.ApprovedVendor;
import com.northwood.product.domain.Product;
import com.northwood.product.domain.ProductId;
import com.northwood.product.domain.ProductRepository;
import com.northwood.product.domain.ProductType;
import com.northwood.product.domain.ValuationClass;
import com.northwood.product.domain.events.ApprovedVendorListChanged;
import com.northwood.product.domain.events.ActiveBomChanged;
import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.product.domain.events.ProductCreated;
import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.product.domain.events.ReorderPolicyChanged;
import com.northwood.product.domain.events.SalesPriceChanged;
import com.northwood.product.domain.events.StandardCostChanged;
import com.northwood.product.domain.events.ValuationClassChanged;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import java.math.BigDecimal;
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

/**
 * Unit tests for {@link ProductService}'s nine @Transactional commands.
 * Real {@link Product} aggregate fixtures + Mockito for the repositories.
 * Each test verifies (1) state mutation on the aggregate, (2) the emitted
 * domain event captured from {@code pullPendingEvents()}, (3) no-op
 * suppression when applicable.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final UUID PID = UUID.randomUUID();
    private static final UUID UOM_EA = UUID.randomUUID();

    @Mock ProductRepository repo;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(repo);
    }

    private Product activeFinishedGood() {
        return Product.reconstitute(
            ProductId.of(PID),
            new Sku("FG-001"), "Finished Good 1", "desc",
            ProductType.FINISHED_GOOD, UOM_EA,
            true, false, true, true,
            Money.of(new BigDecimal("100.00"), "AUD"),
            Money.of(new BigDecimal("60.00"), "AUD"),
            new BigDecimal("5"), new BigDecimal("20"),
            ValuationClass.FINISHED_GOODS, null,
            Product.Status.ACTIVE, 1L,
            List.of()
        );
    }

    private Product activeFinishedGoodWithVendors(List<ApprovedVendor> vendors) {
        return Product.reconstitute(
            ProductId.of(PID),
            new Sku("FG-001"), "Finished Good 1", "desc",
            ProductType.FINISHED_GOOD, UOM_EA,
            true, false, true, true,
            Money.of(new BigDecimal("100.00"), "AUD"),
            Money.of(new BigDecimal("60.00"), "AUD"),
            new BigDecimal("5"), new BigDecimal("20"),
            ValuationClass.FINISHED_GOODS, null,
            Product.Status.ACTIVE, 1L,
            vendors
        );
    }

    private List<DomainEvent> savedEvents() {
        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(repo).save(cap.capture());
        return cap.getValue().pullPendingEvents();
    }

    @Nested class CreateProduct {

        @Test void registers_new_product_and_emits_created_event() {
            ProductView created = service.createProduct(
                "FG-002", "New Product", "desc",
                "finished_good", UOM_EA,
                new BigDecimal("75.00"), new BigDecimal("40.00"),
                "AUD"
            );
            assertThat(created).isNotNull();
            assertThat(created.productId()).isNotNull();

            List<DomainEvent> events = savedEvents();
            assertThat(events).extracting(DomainEvent::eventType)
                .contains(ProductCreated.EVENT_TYPE);
            ProductCreated ev = (ProductCreated) events.stream()
                .filter(e -> e instanceof ProductCreated).findFirst().orElseThrow();
            assertThat(ev.sku()).isEqualTo("FG-002");
            assertThat(ev.productType()).isEqualTo("finished_good");
        }

        @Test void rejects_unknown_product_type() {
            assertThatThrownBy(() -> service.createProduct(
                "X", "n", null, "not_a_type", UOM_EA,
                BigDecimal.ONE, BigDecimal.ONE, "AUD"
            )).isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class ChangeSalesPrice {

        @Test void changes_price_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.changeSalesPrice(PID, new BigDecimal("110.00"), "AUD");

            assertThat(p.salesPrice().amount()).isEqualByComparingTo(new BigDecimal("110.00"));
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SalesPriceChanged.class);
        }

        @Test void no_op_on_unchanged_price() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.changeSalesPrice(PID, new BigDecimal("100.00"), "AUD");

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test void rejects_when_product_not_found() {
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.changeSalesPrice(PID, BigDecimal.ONE, "AUD"))
                .isInstanceOf(ProductService.ProductNotFoundException.class);
        }
    }

    @Nested class ChangeStandardCost {

        @Test void changes_cost_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.changeStandardCost(PID, new BigDecimal("65.50"), "AUD");

            assertThat(p.standardCost().amount()).isEqualByComparingTo(new BigDecimal("65.50"));
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(StandardCostChanged.class);
        }

        @Test void no_op_on_unchanged_cost() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.changeStandardCost(PID, new BigDecimal("60.00"), "AUD");

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class SetReorderPolicy {

        @Test void sets_policy_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.setReorderPolicy(PID, new BigDecimal("10"), new BigDecimal("50"));

            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ReorderPolicyChanged.class);
            ReorderPolicyChanged ev = (ReorderPolicyChanged) events.get(0);
            assertThat(ev.newReorderPoint()).isEqualByComparingTo(new BigDecimal("10"));
            assertThat(ev.newReorderQuantity()).isEqualByComparingTo(new BigDecimal("50"));
        }

        @Test void no_op_on_unchanged_policy() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.setReorderPolicy(PID, new BigDecimal("5"), new BigDecimal("20"));

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class ChangeMakeVsBuy {

        @Test void flips_flags_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.changeMakeVsBuy(PID, /*purchased=*/true, /*manufactured=*/false);

            assertThat(p.isPurchased()).isTrue();
            assertThat(p.isManufactured()).isFalse();
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(MakeVsBuyChanged.class);
        }

        @Test void no_op_on_unchanged_flags() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.changeMakeVsBuy(PID, /*purchased=*/false, /*manufactured=*/true);

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class SetValuationClass {

        @Test void sets_class_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.setValuationClass(PID, "raw_materials");

            assertThat(p.valuationClass()).isEqualTo(ValuationClass.RAW_MATERIALS);
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ValuationClassChanged.class);
        }

        @Test void no_op_on_unchanged_class() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.setValuationClass(PID, "finished_goods");

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test void rejects_unknown_wire_value() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.setValuationClass(PID, "not_a_class"))
                .isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class ActivateBom {

        @Test void activates_bom_and_emits_event() {
            UUID bomId = UUID.randomUUID();
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.activateBom(PID, bomId);

            assertThat(p.activeBomId()).isEqualTo(bomId);
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ActiveBomChanged.class);
        }

        @Test void no_op_on_already_active_bom() {
            UUID bomId = UUID.randomUUID();
            Product p = Product.reconstitute(
                ProductId.of(PID),
                new Sku("FG-001"), "FG", null,
                ProductType.FINISHED_GOOD, UOM_EA,
                true, false, true, true,
                Money.of(new BigDecimal("100"), "AUD"), Money.of(new BigDecimal("60"), "AUD"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, bomId,
                Product.Status.ACTIVE, 1L,
                List.of()
            );
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.activateBom(PID, bomId);

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class SetApprovedVendors {

        private final UUID supA = UUID.randomUUID();
        private final UUID supB = UUID.randomUUID();

        @Test void replaces_vendor_list_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            List<ApprovedVendorCommand> newList = List.of(
                new ApprovedVendorCommand(supA, "SUP-A", "Supplier A", true),
                new ApprovedVendorCommand(supB, "SUP-B", "Supplier B", false)
            );
            service.setApprovedVendors(PID, newList);

            assertThat(p.approvedVendors()).hasSize(2);
            assertThat(p.pullApprovedVendorsDirty()).isTrue();
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ApprovedVendorListChanged.class);
            ApprovedVendorListChanged ev = (ApprovedVendorListChanged) events.get(0);
            assertThat(ev.approvedVendors()).hasSize(2);
        }

        @Test void no_op_on_same_vendor_set() {
            ApprovedVendor existing = new ApprovedVendor(supA, "SUP-A", "Supplier A", true);
            Product p = activeFinishedGoodWithVendors(List.of(existing));
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.setApprovedVendors(PID, List.of(
                new ApprovedVendorCommand(supA, "SUP-A", "Supplier A", true)
            ));

            verify(repo).save(p);
            assertThat(p.pullPendingEvents()).isEmpty();
            assertThat(p.pullApprovedVendorsDirty()).isFalse();
        }

        @Test void rejects_on_discontinued_product() {
            Product p = Product.reconstitute(
                ProductId.of(PID),
                new Sku("FG-001"), "FG", null,
                ProductType.FINISHED_GOOD, UOM_EA,
                true, false, true, true,
                Money.of(new BigDecimal("100"), "AUD"), Money.of(new BigDecimal("60"), "AUD"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null,
                Product.Status.DISCONTINUED, 2L,
                List.of()
            );
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.setApprovedVendors(PID, List.of(
                new ApprovedVendorCommand(supA, "SUP-A", "Supplier A", true)
            ))).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("discontinued");

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class Discontinue {

        @Test void marks_discontinued_and_emits_event() {
            Product p = activeFinishedGood();
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.of(p));

            service.discontinue(PID);

            assertThat(p.status()).isEqualTo(Product.Status.DISCONTINUED);
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ProductDiscontinued.class);
        }

        @Test void rejects_when_product_not_found() {
            when(repo.findById(ProductId.of(PID))).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.discontinue(PID))
                .isInstanceOf(ProductService.ProductNotFoundException.class);
            verify(repo, times(0)).save(org.mockito.ArgumentMatchers.any());
        }
    }
}
