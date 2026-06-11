package com.northwood.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CustomerInvoiceTest {

    private static final UUID SO = UUID.randomUUID();
    private static final UUID SHIPMENT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static CustomerInvoiceLine line(BigDecimal qty, BigDecimal price) {
        BigDecimal lineTotal = qty.multiply(price);
        return new CustomerInvoiceLine(
            UUID.randomUUID(), 10, SO_LINE,
            PRODUCT, "FG-X", "X",
            qty, price,
            BigDecimal.ZERO, BigDecimal.ZERO,
            lineTotal
        );
    }

    @Nested
    class Create {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, CUSTOMER, "CUST", "Cust", Currencies.AUD, List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_sales_order() {
            assertThatThrownBy(() -> CustomerInvoice.create(
                "INV-001", null, SHIPMENT, CUSTOMER, "CUST", "Cust", Currencies.AUD,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_customer() {
            assertThatThrownBy(() -> CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, null, "CUST", "Cust", Currencies.AUD,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void posts_status_directly_to_posted() {
            CustomerInvoice ci = CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, CUSTOMER, "CUST", "Cust", Currencies.AUD,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            );
            assertThat(ci.status()).isEqualTo(CustomerInvoice.Status.POSTED);
        }

        @Test void totals_summed_from_lines() {
            CustomerInvoice ci = CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, CUSTOMER, "CUST", "Cust", Currencies.AUD,
                List.of(
                    line(new BigDecimal("2"), new BigDecimal("100.00")),  // 200
                    line(new BigDecimal("3"), new BigDecimal("50.00"))    // 150
                )
            );
            assertThat(ci.subtotalAmount()).isEqualByComparingTo(new BigDecimal("350.00"));
            assertThat(ci.totalAmount()).isEqualByComparingTo(new BigDecimal("350.00"));
        }

        @Test void emits_CustomerInvoiceCreated_with_routing_keys() {
            CustomerInvoice ci = CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, CUSTOMER, "CUST", "Cust", Currencies.AUD,
                List.of(line(BigDecimal.ONE, new BigDecimal("100")))
            );
            List<DomainEvent> events = ci.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerInvoiceCreated.class);
            CustomerInvoiceCreated e = (CustomerInvoiceCreated) events.get(0);
            assertThat(e.salesOrderHeaderId()).isEqualTo(SO);
            assertThat(e.shipmentHeaderId()).isEqualTo(SHIPMENT);
            assertThat(e.customerId()).isEqualTo(CUSTOMER);
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(e.status()).isEqualTo("posted");
        }

        @Test void carries_shipment_header_id_on_aggregate() {
            CustomerInvoice ci = CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, CUSTOMER, "CUST", "Cust", Currencies.AUD,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            );
            assertThat(ci.shipmentHeaderId()).isEqualTo(SHIPMENT);
        }

        @Test void prepayment_invoice_has_null_shipment_header_id() {
            CustomerInvoice ci = CustomerInvoice.createPrepayment(
                "INV-001", SO, CUSTOMER, "CUST", "Cust", Currencies.AUD,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            );
            assertThat(ci.shipmentHeaderId()).isNull();
            CustomerInvoiceCreated e = (CustomerInvoiceCreated) ci.pullPendingEvents().get(0);
            assertThat(e.shipmentHeaderId()).isNull();
        }

        @Test void defaults_currency_to_AUD_when_null() {
            CustomerInvoice ci = CustomerInvoice.create(
                "INV-001", SO, SHIPMENT, CUSTOMER, "CUST", "Cust", null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            );
            assertThat(ci.currencyCode()).isEqualTo(Currencies.AUD);
        }
    }
}
