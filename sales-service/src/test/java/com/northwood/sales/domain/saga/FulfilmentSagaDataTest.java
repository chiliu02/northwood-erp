package com.northwood.sales.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FulfilmentSagaDataTest {

    @Nested
    class CompactConstructor {
        @Test void defaults_null_fields() {
            FulfilmentSagaData d = new FulfilmentSagaData(null, null, null, null);
            assertThat(d.inventoryCancellationAcked()).isFalse();
            assertThat(d.manufacturingCancellationAcked()).isFalse();
            assertThat(d.paymentTerms()).isNull();                       // null = legacy fallback (on_shipment)
            assertThat(d.outstandingReplenishmentLineIds()).isEmpty();
        }

        @Test void none_factory_yields_empty_data() {
            FulfilmentSagaData d = FulfilmentSagaData.none();
            assertThat(d.outstandingReplenishmentLineIds()).isEmpty();
            assertThat(d.allReplenishmentLinesFulfilled()).isTrue();
            assertThat(d.bothCancellationAcksReceived()).isFalse();
        }
    }

    @Nested
    class PaymentTerms {
        @Test void stamps_terms_on_immutable_copy() {
            FulfilmentSagaData d = FulfilmentSagaData.none().withPaymentTerms("prepayment");
            assertThat(d.paymentTerms()).isEqualTo("prepayment");
        }
    }

    @Nested
    class OutstandingReplenishmentLines {
        @Test void stamps_the_set() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingReplenishmentLineIds(Set.of(a, b));
            assertThat(d.outstandingReplenishmentLineIds()).containsExactlyInAnyOrder(a, b);
            assertThat(d.allReplenishmentLinesFulfilled()).isFalse();
        }

        @Test void removing_each_line_empties_the_set() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingReplenishmentLineIds(Set.of(a, b))
                .withReplenishmentLineFulfilled(a);
            assertThat(d.outstandingReplenishmentLineIds()).containsExactly(b);
            assertThat(d.allReplenishmentLinesFulfilled()).isFalse();

            d = d.withReplenishmentLineFulfilled(b);
            assertThat(d.outstandingReplenishmentLineIds()).isEmpty();
            assertThat(d.allReplenishmentLinesFulfilled()).isTrue();
        }

        @Test void removing_absent_line_is_idempotent() {
            UUID a = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingReplenishmentLineIds(Set.of(a))
                .withReplenishmentLineFulfilled(UUID.randomUUID());   // not in the set
            assertThat(d.outstandingReplenishmentLineIds()).containsExactly(a);
        }
    }

    @Nested
    class CompensationAcks {
        @Test void both_acks_required() {
            FulfilmentSagaData inv = FulfilmentSagaData.none().withInventoryCancellationAcked();
            assertThat(inv.bothCancellationAcksReceived()).isFalse();

            FulfilmentSagaData both = inv.withManufacturingCancellationAcked();
            assertThat(both.bothCancellationAcksReceived()).isTrue();
        }

        @Test void acks_preserve_other_fields() {
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withPaymentTerms("on_shipment")
                .withInventoryCancellationAcked();
            assertThat(d.paymentTerms()).isEqualTo("on_shipment");
            assertThat(d.inventoryCancellationAcked()).isTrue();
        }
    }
}
