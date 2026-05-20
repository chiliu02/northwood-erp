package com.northwood.manufacturing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.events.BomActivated;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BomTest {

    private static final UUID FINISHED = UUID.randomUUID();
    private static final UUID COMPONENT_A = UUID.randomUUID();
    private static final UUID COMPONENT_B = UUID.randomUUID();

    private static Bom newDraft() {
        return Bom.draft(FINISHED, "FG-001", "Finished Good 1", "1");
    }

    private static BomLine.Spec lineSpec(UUID componentProductId, String sku) {
        return new BomLine.Spec(
            componentProductId, sku, "Name " + sku, Bom.ComponentKind.RAW,
            new BigDecimal("2.000"), new BigDecimal("0.05")
        );
    }

    @Nested
    class Draft {

        @Test void creates_bom_in_draft_status_with_no_lines_no_events() {
            Bom bom = newDraft();
            assertThat(bom.status()).isEqualTo(Bom.Status.DRAFT);
            assertThat(bom.lines()).isEmpty();
            assertThat(bom.aggregateVersion()).isZero();
            assertThat(bom.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_null_finishedProductId() {
            assertThatThrownBy(() -> Bom.draft(null, "FG-001", "n", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finishedProductId");
        }

        @Test void rejects_null_finishedProductSku() {
            assertThatThrownBy(() -> Bom.draft(FINISHED, null, "n", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finishedProductSku");
        }

        @Test void rejects_null_finishedProductName() {
            assertThatThrownBy(() -> Bom.draft(FINISHED, "FG-001", null, "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finishedProductName");
        }

        @Test void rejects_null_version() {
            assertThatThrownBy(() -> Bom.draft(FINISHED, "FG-001", "n", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
        }

        @Test void rejects_blank_finishedProductSku() {
            assertThatThrownBy(() -> Bom.draft(FINISHED, "  ", "n", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finishedProductSku");
        }

        @Test void rejects_blank_finishedProductName() {
            assertThatThrownBy(() -> Bom.draft(FINISHED, "FG-001", "", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finishedProductName");
        }

        @Test void rejects_blank_version() {
            assertThatThrownBy(() -> Bom.draft(FINISHED, "FG-001", "n", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
        }
    }

    @Nested
    class AddLine {

        @Test void appends_first_line_with_lineNumber_1_and_no_events() {
            Bom bom = newDraft();
            BomLine line = bom.addLine(lineSpec(COMPONENT_A, "RM-A"));
            assertThat(line.lineNumber()).isEqualTo(1);
            assertThat(line.componentProductId()).isEqualTo(COMPONENT_A);
            assertThat(bom.lines()).hasSize(1);
            assertThat(bom.pullPendingEvents()).isEmpty();
        }

        @Test void increments_lineNumber_monotonically() {
            Bom bom = newDraft();
            bom.addLine(lineSpec(COMPONENT_A, "RM-A"));
            BomLine second = bom.addLine(lineSpec(COMPONENT_B, "RM-B"));
            assertThat(second.lineNumber()).isEqualTo(2);
            assertThat(bom.lines()).hasSize(2);
        }

        @Test void tracks_added_line_for_repository_save() {
            Bom bom = newDraft();
            BomLine line = bom.addLine(lineSpec(COMPONENT_A, "RM-A"));
            List<BomLine> added = bom.pullAddedLines();
            assertThat(added).containsExactly(line);
            assertThat(bom.pullAddedLines()).isEmpty();  // pull resets
        }

        @Test void rejects_when_status_is_active() {
            Bom bom = activeBomWithOneLine();
            assertThatThrownBy(() -> bom.addLine(lineSpec(COMPONENT_B, "RM-B")))
                .isInstanceOf(Bom.BomNotEditableException.class)
                .hasMessageContaining("ACTIVE");
        }

        @Test void rejects_when_status_is_inactive() {
            Bom bom = Bom.reconstitute(
                BomId.newId(), FINISHED, "FG-001", "n", "1",
                Bom.Status.INACTIVE, List.of(), 5L
            );
            assertThatThrownBy(() -> bom.addLine(lineSpec(COMPONENT_A, "RM-A")))
                .isInstanceOf(Bom.BomNotEditableException.class)
                .hasMessageContaining("INACTIVE");
        }

        @Test void rejects_self_reference_component_equals_finished_product() {
            Bom bom = newDraft();
            assertThatThrownBy(() -> bom.addLine(lineSpec(FINISHED, "FG-001")))
                .isInstanceOf(Bom.BomCycleException.class)
                .hasMessageContaining("Component cannot equal");
        }

        @Test void rejects_null_spec() {
            Bom bom = newDraft();
            assertThatThrownBy(() -> bom.addLine(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spec");
        }

        @Test void rejects_spec_with_null_componentProductId() {
            Bom bom = newDraft();
            BomLine.Spec bad = new BomLine.Spec(
                null, "RM", "n", Bom.ComponentKind.RAW, BigDecimal.ONE, BigDecimal.ZERO
            );
            assertThatThrownBy(() -> bom.addLine(bad))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("componentProductId");
        }
    }

    @Nested
    class RemoveLine {

        @Test void removes_an_added_line_and_does_not_record_for_repository_delete() {
            Bom bom = newDraft();
            BomLine line = bom.addLine(lineSpec(COMPONENT_A, "RM-A"));
            bom.pullAddedLines();  // simulate that nothing has been persisted yet, then re-add
            BomLine reAdded = bom.addLine(lineSpec(COMPONENT_B, "RM-B"));

            boolean removed = bom.removeLine(reAdded.id());
            assertThat(removed).isTrue();
            assertThat(bom.lines()).hasSize(1);
            assertThat(bom.pullAddedLines()).isEmpty();           // reAdded was pulled out of addedLines
            assertThat(bom.pullRemovedLineIds()).isEmpty();       // never persisted, no DELETE
            assertThat(line).isNotNull();                          // line is still on the BOM
        }

        @Test void records_removed_line_for_repository_delete_when_loaded_from_db() {
            BomLineId lineIdA = BomLineId.newId();
            BomLine loaded = new BomLine(
                lineIdA, 1, COMPONENT_A, "RM-A", "Raw A", Bom.ComponentKind.RAW,
                new BigDecimal("2"), BigDecimal.ZERO
            );
            Bom bom = Bom.reconstitute(
                BomId.newId(), FINISHED, "FG-001", "n", "1",
                Bom.Status.DRAFT, List.of(loaded), 3L
            );

            boolean removed = bom.removeLine(lineIdA);
            assertThat(removed).isTrue();
            assertThat(bom.lines()).isEmpty();
            assertThat(bom.pullRemovedLineIds()).containsExactly(lineIdA);
        }

        @Test void returns_false_when_line_not_found() {
            Bom bom = newDraft();
            assertThat(bom.removeLine(BomLineId.newId())).isFalse();
        }

        @Test void rejects_on_active_status() {
            Bom bom = activeBomWithOneLine();
            assertThatThrownBy(() -> bom.removeLine(bom.lines().get(0).id()))
                .isInstanceOf(Bom.BomNotEditableException.class)
                .hasMessageContaining("ACTIVE");
        }

        @Test void rejects_null_bomLineId() {
            Bom bom = newDraft();
            assertThatThrownBy(() -> bom.removeLine(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bomLineId");
        }
    }

    @Nested
    class Activate {

        @Test void flips_status_to_active_and_emits_BomActivated() {
            Bom bom = newDraft();
            bom.addLine(lineSpec(COMPONENT_A, "RM-A"));

            bom.activate();

            assertThat(bom.status()).isEqualTo(Bom.Status.ACTIVE);
            List<DomainEvent> events = bom.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(BomActivated.class);
            BomActivated e = (BomActivated) events.get(0);
            assertThat(e.aggregateId()).isEqualTo(bom.id().value());
            assertThat(e.finishedProductId()).isEqualTo(FINISHED);
            assertThat(e.finishedProductSku()).isEqualTo("FG-001");
            assertThat(e.version()).isEqualTo("1");
        }

        @Test void no_op_when_already_active_emits_nothing() {
            Bom bom = activeBomWithOneLine();
            bom.activate();
            assertThat(bom.status()).isEqualTo(Bom.Status.ACTIVE);
            assertThat(bom.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_from_inactive_status() {
            Bom bom = Bom.reconstitute(
                BomId.newId(), FINISHED, "FG-001", "n", "1",
                Bom.Status.INACTIVE, List.of(), 5L
            );
            assertThatThrownBy(bom::activate)
                .isInstanceOf(Bom.BomNotEditableException.class)
                .hasMessageContaining("INACTIVE");
        }

        @Test void rejects_when_draft_has_no_lines() {
            Bom bom = newDraft();
            assertThatThrownBy(bom::activate)
                .isInstanceOf(Bom.BomNotEditableException.class)
                .hasMessageContaining("no lines");
        }
    }

    @Nested
    class Reconstitute {

        @Test void preserves_state_and_emits_nothing() {
            BomId id = BomId.newId();
            BomLineId lineId = BomLineId.newId();
            BomLine line = new BomLine(
                lineId, 5, COMPONENT_A, "RM-A", "Raw A", Bom.ComponentKind.RAW,
                new BigDecimal("2"), BigDecimal.ZERO
            );
            Bom bom = Bom.reconstitute(
                id, FINISHED, "FG-001", "Finished", "v3",
                Bom.Status.ACTIVE, List.of(line), 7L
            );
            assertThat(bom.id()).isEqualTo(id);
            assertThat(bom.aggregateVersion()).isEqualTo(7L);
            assertThat(bom.lines()).hasSize(1);
            assertThat(bom.pullPendingEvents()).isEmpty();
            assertThat(bom.pullAddedLines()).isEmpty();
            assertThat(bom.pullRemovedLineIds()).isEmpty();
        }
    }

    private static Bom activeBomWithOneLine() {
        BomLine line = new BomLine(
            BomLineId.newId(), 1, COMPONENT_A, "RM-A", "Raw A", Bom.ComponentKind.RAW,
            new BigDecimal("2"), BigDecimal.ZERO
        );
        return Bom.reconstitute(
            BomId.newId(), FINISHED, "FG-001", "n", "1",
            Bom.Status.ACTIVE, List.of(line), 5L
        );
    }
}
