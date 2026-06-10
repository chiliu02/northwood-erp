package com.northwood.testharness.projection;

import static com.northwood.testharness.dsl.Dsl.a_work_order;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.planner;
import static com.northwood.testharness.dsl.Dsl.production_board;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Scenario.scenario;

import com.northwood.manufacturing.domain.events.WorkOrderPriorityChanged;
import org.junit.jupiter.api.Test;

/**
 * Work-order priority projection cascade, ported from {@code SetPriorityCascadeTest}
 * (REQ-MFG-070, REQ-RPT-020). A planner reprioritises a released work order in
 * manufacturing; the single {@code WorkOrderPriorityChanged} event propagates to
 * reporting's production-planning board read model.
 *
 * <p>Partial-fit: the imperative twin also asserts the manufacturing outbox fully
 * drained — that is {@code settle()}'s job, not a business fact, so it is dropped
 * here. The cross-service cascade outcome (the board shows the new priority) is the
 * ported business fact.
 */
class SetPriorityCascadeDslTest {

    @Test
    void priority_change_propagates_to_the_production_planning_board() {
        scenario("reprioritising a work order cascades to the production-planning board")
            .given(a_work_order("WO-PRIO-001").forProduct("FG-001", "Finished Good 1")
                .plannedQuantity(qty(10)).released())

            // ── trigger: the planner marks it urgent ──
            .when(planner().sets_priority_of("WO-PRIO-001").to("urgent"))
            // ── outcome: the board read model reflects the new priority ──
            .then(production_board("WO-PRIO-001").has_priority("urgent"))
            .and(events_published(WorkOrderPriorityChanged.EVENT_TYPE));
    }
}
