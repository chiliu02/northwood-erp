package com.northwood.testharness.p2p;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.product.domain.ReplenishmentStrategy;
import com.northwood.purchasing.application.PurchaseRequisitionService.ToOrderProductManualPurchaseException;
import com.northwood.purchasing.application.dto.CreateRequisitionCommand;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.application.dto.StockReplenishmentCommand;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.PurchasingTestKit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Guard: a to-order product must not be bought on a <em>manual</em> requisition —
 * its received stock would land in free ATP and could never be reserved by a sales
 * order (every to-order line raises its own dedicated, order-pegged supply), so it
 * would orphan as dead inventory. The system order-pegged buy flow
 * ({@code createForStockReplenishment}) is the legitimate path and must stay open
 * for exactly such products.
 */
class PurchaseRequisitionToOrderGuardTest {

    @Test
    void manual_requisition_for_a_to_order_product_is_rejected() {
        PurchasingTestKit purchasing = new PurchasingTestKit(new SynchronousBus(), new ObjectMapper());

        UUID toOrderProduct = UUID.randomUUID();
        purchasing.toOrderProducts.put(toOrderProduct, ReplenishmentStrategy.TO_ORDER.dbValue());

        assertThatThrownBy(() -> purchasing.requisitionService.createManual(new CreateRequisitionCommand(
            "PR-TO-ORDER",
            List.of(new RequisitionLineRequest(
                toOrderProduct, "FG-CARPET-001", "Custom-design Carpet",
                new BigDecimal("1"), null
            ))
        )))
            .isInstanceOf(ToOrderProductManualPurchaseException.class)
            .hasMessageContaining("to-order");
    }

    @Test
    void manual_requisition_for_a_to_stock_product_is_accepted() {
        PurchasingTestKit purchasing = new PurchasingTestKit(new SynchronousBus(), new ObjectMapper());

        // Unset strategy → fail-open → not-to-order → allowed.
        UUID toStockProduct = UUID.randomUUID();

        assertThatCode(() -> purchasing.requisitionService.createManual(new CreateRequisitionCommand(
            "PR-TO-STOCK",
            List.of(new RequisitionLineRequest(
                toStockProduct, "RM-101", "Raw Material 101",
                new BigDecimal("10"), null
            ))
        ))).doesNotThrowAnyException();
    }

    @Test
    void system_order_pegged_buy_for_a_to_order_product_is_not_blocked() {
        PurchasingTestKit purchasing = new PurchasingTestKit(new SynchronousBus(), new ObjectMapper());

        UUID toOrderProduct = UUID.randomUUID();
        purchasing.toOrderProducts.put(toOrderProduct, ReplenishmentStrategy.TO_ORDER.dbValue());

        // The system replenishment path is how a to-order product is legitimately
        // purchased — it must NOT be gated by the manual-path guard.
        Optional<UUID> prId = purchasing.requisitionService.createForStockReplenishment(
            new StockReplenishmentCommand(
                "PR-PEGGED",
                UUID.randomUUID(),   // replenishmentRequestId
                UUID.randomUUID(),   // sourceSalesOrderHeaderId
                List.of(new RequisitionLineRequest(
                    toOrderProduct, "FG-CARPET-001", "Custom-design Carpet",
                    new BigDecimal("1"), null
                ))
            ));

        assertThat(prId).isPresent();
    }
}
