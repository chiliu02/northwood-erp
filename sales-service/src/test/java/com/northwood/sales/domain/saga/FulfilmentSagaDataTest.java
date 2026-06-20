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
            FulfilmentSagaData d = new FulfilmentSagaData(null, null, null, null, null, null, null, null);
            assertThat(d.paymentTerms()).isNull();                       // null = legacy fallback (on_shipment)
            assertThat(d.outstandingReplenishmentLineIds()).isEmpty();
            assertThat(d.sawNonPeggedReplenishment()).isFalse();
            assertThat(d.requestedDeliveryDate()).isNull();              // null = no fence gating (reserve immediately)
            assertThat(d.isOrderShipped()).isFalse();                    // completion-gate flags default false
            assertThat(d.isOrderSettled()).isFalse();
            assertThat(d.isReadyToComplete()).isFalse();
            assertThat(d.outstandingCompensationLegs()).isEmpty();       // no compensation in flight
            assertThat(d.failedCompensationLegs()).isEmpty();
        }

        @Test void none_factory_yields_empty_data() {
            FulfilmentSagaData d = FulfilmentSagaData.none();
            assertThat(d.outstandingReplenishmentLineIds()).isEmpty();
            assertThat(d.allReplenishmentLinesFulfilled()).isTrue();
            assertThat(d.allCompensationLegsAcked()).isTrue();
            assertThat(d.hasCompensationFailures()).isFalse();
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
    class RequestedDeliveryDate {
        @Test void stamps_date_on_immutable_copy() {
            FulfilmentSagaData d = FulfilmentSagaData.none().withRequestedDeliveryDate("2026-07-01");
            assertThat(d.requestedDeliveryDate()).isEqualTo("2026-07-01");
        }

        @Test void survives_other_mutations() {
            // The gate reads need-by at requestStockReservation, which can run
            // after prepayment parking + outstanding-replenishment churn — the
            // date must thread through every with* builder.
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withRequestedDeliveryDate("2026-07-01")
                .withPaymentTerms("prepayment")
                .withOutstandingReplenishmentLineIds(Set.of(UUID.randomUUID()))
                .withOutstandingCompensationLegs(Set.of("purchasing:" + UUID.randomUUID()));
            assertThat(d.requestedDeliveryDate()).isEqualTo("2026-07-01");
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
                .withReplenishmentLineFulfilled(a, false);
            assertThat(d.outstandingReplenishmentLineIds()).containsExactly(b);
            assertThat(d.allReplenishmentLinesFulfilled()).isFalse();

            d = d.withReplenishmentLineFulfilled(b, false);
            assertThat(d.outstandingReplenishmentLineIds()).isEmpty();
            assertThat(d.allReplenishmentLinesFulfilled()).isTrue();
        }

        @Test void removing_absent_line_is_idempotent() {
            UUID a = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingReplenishmentLineIds(Set.of(a))
                .withReplenishmentLineFulfilled(UUID.randomUUID(), false);   // not in the set
            assertThat(d.outstandingReplenishmentLineIds()).containsExactly(a);
        }

        @Test void pegged_fulfilment_does_not_latch_saw_non_pegged() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingReplenishmentLineIds(Set.of(a, b))
                .withReplenishmentLineFulfilled(a, true)
                .withReplenishmentLineFulfilled(b, true);
            assertThat(d.allReplenishmentLinesFulfilled()).isTrue();
            assertThat(d.sawNonPeggedReplenishment()).isFalse();
        }

        @Test void one_non_pegged_fulfilment_latches_saw_non_pegged() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingReplenishmentLineIds(Set.of(a, b))
                .withReplenishmentLineFulfilled(a, true)
                .withReplenishmentLineFulfilled(b, false);
            assertThat(d.sawNonPeggedReplenishment()).isTrue();
        }
    }

    @Nested
    class CompletionGate {
        @Test void both_legs_required_to_be_ready() {
            FulfilmentSagaData shippedOnly = FulfilmentSagaData.none().withOrderShipped();
            assertThat(shippedOnly.isOrderShipped()).isTrue();
            assertThat(shippedOnly.isReadyToComplete()).isFalse();

            FulfilmentSagaData settledOnly = FulfilmentSagaData.none().withOrderSettled();
            assertThat(settledOnly.isOrderSettled()).isTrue();
            assertThat(settledOnly.isReadyToComplete()).isFalse();

            FulfilmentSagaData both = FulfilmentSagaData.none().withOrderShipped().withOrderSettled();
            assertThat(both.isReadyToComplete()).isTrue();
        }

        @Test void flags_are_order_independent() {
            // ship-then-settle and settle-then-ship both reach ready-to-complete.
            assertThat(FulfilmentSagaData.none().withOrderShipped().withOrderSettled().isReadyToComplete()).isTrue();
            assertThat(FulfilmentSagaData.none().withOrderSettled().withOrderShipped().isReadyToComplete()).isTrue();
        }

        @Test void gate_flags_survive_other_mutations() {
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOrderSettled()
                .withPaymentTerms("deposit")
                .withOutstandingReplenishmentLineIds(Set.of(UUID.randomUUID()));
            assertThat(d.isOrderSettled()).isTrue();
        }
    }

    @Nested
    class CompensationLegDrain {
        @Test void empty_set_means_nothing_to_compensate() {
            FulfilmentSagaData d = FulfilmentSagaData.none();
            assertThat(d.allCompensationLegsAcked()).isTrue();
            assertThat(d.hasCompensationFailures()).isFalse();
        }

        @Test void draining_each_leg_empties_the_set() {
            String po = "purchasing:" + UUID.randomUUID();
            String wo = "manufacturing:" + UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingCompensationLegs(Set.of(po, wo));
            assertThat(d.allCompensationLegsAcked()).isFalse();

            d = d.withCompensationLegAcked(po, false);
            assertThat(d.outstandingCompensationLegs()).containsExactly(wo);
            assertThat(d.allCompensationLegsAcked()).isFalse();

            d = d.withCompensationLegAcked(wo, false);
            assertThat(d.allCompensationLegsAcked()).isTrue();
            assertThat(d.hasCompensationFailures()).isFalse();
        }

        @Test void draining_absent_leg_is_idempotent() {
            String po = "purchasing:" + UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingCompensationLegs(Set.of(po))
                .withCompensationLegAcked("manufacturing:" + UUID.randomUUID(), false);   // not in the set
            assertThat(d.outstandingCompensationLegs()).containsExactly(po);
        }

        @Test void a_failed_leg_is_recorded_as_an_uncompensatable_leaf() {
            String po = "purchasing:" + UUID.randomUUID();
            String wo = "manufacturing:" + UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withOutstandingCompensationLegs(Set.of(po, wo))
                .withCompensationLegAcked(po, false)
                .withCompensationLegAcked(wo, true);
            assertThat(d.allCompensationLegsAcked()).isTrue();
            assertThat(d.hasCompensationFailures()).isTrue();
            assertThat(d.failedCompensationLegs()).containsExactly(wo);
        }

        @Test void legs_preserve_other_fields() {
            String po = "purchasing:" + UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withPaymentTerms("on_shipment")
                .withOutstandingCompensationLegs(Set.of(po));
            assertThat(d.paymentTerms()).isEqualTo("on_shipment");
            assertThat(d.outstandingCompensationLegs()).containsExactly(po);
        }
    }
}
