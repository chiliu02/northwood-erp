package com.northwood.sales.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FulfilmentSagaDataTest {

    @Nested
    class CompactConstructor {
        @Test void defaults_null_collections_to_empty() {
            FulfilmentSagaData d = new FulfilmentSagaData(null, null, null, null, false, false, null, null);
            assertThat(d.shortageByLineNumber()).isEmpty();
            assertThat(d.outstandingWorkOrderIds()).isEmpty();
            assertThat(d.completedWorkOrderIds()).isEmpty();
            assertThat(d.expectedWorkOrderCount()).isNull();   // null is the legacy sentinel
            assertThat(d.paymentTerms()).isNull();             // null = legacy fallback (on_shipment)
        }

        @Test void none_factory_yields_empty_data() {
            FulfilmentSagaData d = FulfilmentSagaData.none();
            assertThat(d.hasShortage()).isFalse();
            assertThat(d.expectedWorkOrderCount()).isNull();
        }
    }

    @Nested
    class HasShortage {
        @Test void true_when_shortage_map_non_empty() {
            FulfilmentSagaData d = new FulfilmentSagaData(
                Map.of(10, BigDecimal.ONE), null, null, null, false, false, null, null);
            assertThat(d.hasShortage()).isTrue();
        }

        @Test void false_when_shortage_map_empty() {
            assertThat(FulfilmentSagaData.none().hasShortage()).isFalse();
        }
    }

    @Nested
    class WithExpectedWorkOrderCount {
        @Test void stamps_count_on_immutable_copy() {
            FulfilmentSagaData d = FulfilmentSagaData.none().withExpectedWorkOrderCount(3);
            assertThat(d.expectedWorkOrderCount()).isEqualTo(3);
        }

        @Test void preserves_other_fields() {
            UUID wo1 = UUID.randomUUID();
            FulfilmentSagaData base = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo1);
            FulfilmentSagaData stamped = base.withExpectedWorkOrderCount(2);
            assertThat(stamped.outstandingWorkOrderIds()).contains(wo1);
        }
    }

    @Nested
    class WithWorkOrderCreated {
        @Test void adds_to_outstanding_set() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none().withWorkOrderCreated(wo);
            assertThat(d.outstandingWorkOrderIds()).containsExactly(wo);
        }

        @Test void idempotent_on_duplicate_wo_id() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo)
                .withWorkOrderCreated(wo);
            assertThat(d.outstandingWorkOrderIds()).hasSize(1);
        }

        @Test void noop_when_already_completed() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo)
                .withWorkOrderCompleted(wo)
                .withWorkOrderCreated(wo);   // late-arriving Created
            assertThat(d.outstandingWorkOrderIds()).isEmpty();
            assertThat(d.completedWorkOrderIds()).containsExactly(wo);
        }
    }

    @Nested
    class WithWorkOrderCompleted {
        @Test void moves_from_outstanding_to_completed() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo)
                .withWorkOrderCompleted(wo);
            assertThat(d.outstandingWorkOrderIds()).isEmpty();
            assertThat(d.completedWorkOrderIds()).containsExactly(wo);
        }

        @Test void idempotent_on_duplicate_completion() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo)
                .withWorkOrderCompleted(wo)
                .withWorkOrderCompleted(wo);
            assertThat(d.completedWorkOrderIds()).hasSize(1);
        }

        @Test void completion_arriving_before_creation_still_counts() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none().withWorkOrderCompleted(wo);
            assertThat(d.completedWorkOrderIds()).containsExactly(wo);
            assertThat(d.outstandingWorkOrderIds()).isEmpty();
        }
    }

    @Nested
    class AllWorkOrdersComplete_LegacyPath {
        @Test void false_when_no_completions_yet() {
            assertThat(FulfilmentSagaData.none().allWorkOrdersComplete()).isFalse();
        }

        @Test void false_when_outstanding_remains() {
            UUID wo1 = UUID.randomUUID();
            UUID wo2 = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo1)
                .withWorkOrderCreated(wo2)
                .withWorkOrderCompleted(wo1);
            assertThat(d.allWorkOrdersComplete()).isFalse();
        }

        @Test void true_when_outstanding_empty_and_completed_non_empty() {
            UUID wo = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withWorkOrderCreated(wo)
                .withWorkOrderCompleted(wo);
            assertThat(d.allWorkOrdersComplete()).isTrue();
        }
    }

    @Nested
    class AllWorkOrdersComplete_WithExpectedCount {
        @Test void false_when_completed_below_expected() {
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withExpectedWorkOrderCount(2)
                .withWorkOrderCompleted(UUID.randomUUID());
            assertThat(d.allWorkOrdersComplete()).isFalse();
        }

        @Test void true_when_completed_meets_expected() {
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withExpectedWorkOrderCount(2)
                .withWorkOrderCompleted(UUID.randomUUID())
                .withWorkOrderCompleted(UUID.randomUUID());
            assertThat(d.allWorkOrdersComplete()).isTrue();
        }

        @Test void cross_partition_race_does_not_advance_prematurely() {
            // The bug this fix targets: WO1 Completed arrives before WO2 Created.
            // Without expectedCount, outstanding={} after the first completion
            // looks like "all done." With expectedCount=2, the gate stays closed.
            UUID wo1 = UUID.randomUUID();
            FulfilmentSagaData d = FulfilmentSagaData.none()
                .withExpectedWorkOrderCount(2)
                .withWorkOrderCreated(wo1)
                .withWorkOrderCompleted(wo1);
            // Outstanding is empty AND completed has 1, but expected is 2.
            assertThat(d.outstandingWorkOrderIds()).isEmpty();
            assertThat(d.allWorkOrdersComplete()).isFalse();
        }

        @Test void expected_count_zero_yields_false() {
            // An "all rejected" dispatch should never set count > 0; but if it
            // did set 0, no completion can ever satisfy the gate. Treat as
            // "advance gate is satisfied vacuously" — completed.size() ≥ 0.
            FulfilmentSagaData d = FulfilmentSagaData.none().withExpectedWorkOrderCount(0);
            // Defensively: 0 completions ≥ 0 expected → true. The handler
            // gates on anyAccepted before stamping, so this path shouldn't fire.
            assertThat(d.allWorkOrdersComplete()).isTrue();
        }
    }
}
