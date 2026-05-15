package com.northwood.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.BomEditService.AddLineCommand;
import com.northwood.manufacturing.application.BomEditService.BomComponentDiscontinuedException;
import com.northwood.manufacturing.application.BomEditService.BomCycleException;
import com.northwood.manufacturing.application.BomEditService.BomLineNotFoundException;
import com.northwood.manufacturing.application.BomEditService.BomNotEditableException;
import com.northwood.manufacturing.application.BomEditService.BomNotFoundException;
import com.northwood.manufacturing.application.BomEditService.CreateBomDraftCommand;
import com.northwood.manufacturing.domain.Bom;
import com.northwood.manufacturing.domain.BomCycleDetector;
import com.northwood.manufacturing.domain.BomId;
import com.northwood.manufacturing.domain.BomLine;
import com.northwood.manufacturing.domain.BomLineId;
import com.northwood.manufacturing.domain.BomRepository;
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

@ExtendWith(MockitoExtension.class)
class BomEditServiceTest {

    private static final UUID FINISHED = UUID.randomUUID();
    private static final UUID COMPONENT = UUID.randomUUID();
    private static final UUID OTHER_COMPONENT = UUID.randomUUID();
    private static final UUID HEADER = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

    @Mock BomRepository boms;
    @Mock BomCycleDetector cycleDetector;
    @Mock MaterialsCostRollupService rollup;
    @Mock DiscontinuedProductLookup discontinuedProducts;

    private BomEditService service;

    @BeforeEach
    void setUp() {
        service = new BomEditService(boms, cycleDetector, rollup, discontinuedProducts);
    }

    private Bom draftBom() {
        return Bom.reconstitute(
            BomId.of(HEADER), FINISHED, "FG-001", "Finished Good 1", "1",
            Bom.Status.DRAFT, List.of(), 2L
        );
    }

    private Bom draftBomWithOneLine() {
        BomLine line = new BomLine(
            BomLineId.of(LINE), 1, COMPONENT, "RM-001", "Raw 1", "raw_material",
            new BigDecimal("2"), BigDecimal.ZERO
        );
        return Bom.reconstitute(
            BomId.of(HEADER), FINISHED, "FG-001", "Finished Good 1", "1",
            Bom.Status.DRAFT, List.of(line), 2L
        );
    }

    private Bom activeBom() {
        BomLine line = new BomLine(
            BomLineId.of(LINE), 1, COMPONENT, "RM-001", "Raw 1", "raw_material",
            new BigDecimal("2"), BigDecimal.ZERO
        );
        return Bom.reconstitute(
            BomId.of(HEADER), FINISHED, "FG-001", "Finished Good 1", "1",
            Bom.Status.ACTIVE, List.of(line), 2L
        );
    }

    private CreateBomDraftCommand createDraft(String version) {
        return new CreateBomDraftCommand(FINISHED, "FG-001", "Finished Good 1", version);
    }

    private AddLineCommand addLineCommand(UUID componentProductId) {
        return new AddLineCommand(
            componentProductId, "RM-001", "Raw Material 001", "raw_material",
            new BigDecimal("2.000"), new BigDecimal("0.05")
        );
    }

    @Nested
    class CreateDraft {

        @Test void saves_a_newly_drafted_aggregate() {
            UUID id = service.createDraft(createDraft("v2"));

            ArgumentCaptor<Bom> cap = ArgumentCaptor.forClass(Bom.class);
            verify(boms).save(cap.capture());
            Bom saved = cap.getValue();
            assertThat(saved.id().value()).isEqualTo(id);
            assertThat(saved.status()).isEqualTo(Bom.Status.DRAFT);
            assertThat(saved.version()).isEqualTo("v2");
            assertThat(saved.finishedProductId()).isEqualTo(FINISHED);
        }

        @Test void defaults_version_to_1_when_blank() {
            service.createDraft(createDraft(""));

            ArgumentCaptor<Bom> cap = ArgumentCaptor.forClass(Bom.class);
            verify(boms).save(cap.capture());
            assertThat(cap.getValue().version()).isEqualTo("1");
        }

        @Test void defaults_version_to_1_when_null() {
            service.createDraft(createDraft(null));

            ArgumentCaptor<Bom> cap = ArgumentCaptor.forClass(Bom.class);
            verify(boms).save(cap.capture());
            assertThat(cap.getValue().version()).isEqualTo("1");
        }

        @Test void rejects_null_finished_product_id() {
            CreateBomDraftCommand bad = new CreateBomDraftCommand(null, "FG-001", "x", "1");

            assertThatThrownBy(() -> service.createDraft(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedProductId");
            verifyNoInteractions(boms);
        }

        @Test void rejects_blank_sku_from_aggregate_guard() {
            CreateBomDraftCommand bad = new CreateBomDraftCommand(FINISHED, "  ", "Name", "1");

            assertThatThrownBy(() -> service.createDraft(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedProductSku");
            verifyNoInteractions(boms);
        }

        @Test void rejects_blank_name_from_aggregate_guard() {
            CreateBomDraftCommand bad = new CreateBomDraftCommand(FINISHED, "FG-001", "", "1");

            assertThatThrownBy(() -> service.createDraft(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedProductName");
            verifyNoInteractions(boms);
        }
    }

    @Nested
    class AddLine {

        @Test void mutates_aggregate_saves_and_runs_cycle_check() {
            Bom bom = draftBom();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, HEADER)).thenReturn(false);

            UUID newLineId = service.addLine(HEADER, addLineCommand(COMPONENT));

            assertThat(bom.lines()).hasSize(1);
            assertThat(bom.lines().get(0).id().value()).isEqualTo(newLineId);
            verify(boms).save(bom);
            verify(cycleDetector).wouldCreateCycle(COMPONENT, FINISHED, HEADER);
        }

        @Test void rejects_when_header_does_not_exist() {
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addLine(HEADER, addLineCommand(COMPONENT)))
                .isInstanceOf(BomNotFoundException.class);
            verify(boms, never()).save(any());
        }

        @Test void rejects_on_active_header() {
            Bom bom = activeBom();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            assertThatThrownBy(() -> service.addLine(HEADER, addLineCommand(OTHER_COMPONENT)))
                .isInstanceOf(BomNotEditableException.class)
                .hasMessageContaining("ACTIVE");
            verify(boms, never()).save(any());
            verifyNoInteractions(cycleDetector);
        }

        @Test void rejects_when_component_equals_finished_product() {
            Bom bom = draftBom();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            assertThatThrownBy(() -> service.addLine(HEADER, addLineCommand(FINISHED)))
                .isInstanceOf(BomCycleException.class)
                .hasMessageContaining("Component cannot equal");
            verify(boms, never()).save(any());
            verifyNoInteractions(cycleDetector);
        }

        @Test void rejects_on_post_save_cycle_detection() {
            Bom bom = draftBom();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, HEADER)).thenReturn(true);

            assertThatThrownBy(() -> service.addLine(HEADER, addLineCommand(COMPONENT)))
                .isInstanceOf(BomCycleException.class)
                .hasMessageContaining("close a cycle");
            // save() already happened — the surrounding @Transactional rolls back
            // in production. Here we just verify the throw + the save was attempted.
            verify(boms).save(bom);
        }

        @Test void rejects_when_component_is_discontinued() {
            when(discontinuedProducts.isDiscontinued(COMPONENT)).thenReturn(true);

            assertThatThrownBy(() -> service.addLine(HEADER, addLineCommand(COMPONENT)))
                .isInstanceOf(BomComponentDiscontinuedException.class)
                .hasMessageContaining("RM-001");
            verify(boms, never()).findById(any());
            verify(boms, never()).save(any());
            verifyNoInteractions(cycleDetector);
        }
    }

    @Nested
    class RemoveLine {

        @Test void resolves_header_loads_bom_removes_line_and_saves() {
            Bom bom = draftBomWithOneLine();
            when(boms.findBomIdByLineId(BomLineId.of(LINE))).thenReturn(Optional.of(BomId.of(HEADER)));
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            service.removeLine(LINE);

            assertThat(bom.lines()).isEmpty();
            verify(boms).save(bom);
        }

        @Test void rejects_when_line_does_not_exist_at_lookup() {
            when(boms.findBomIdByLineId(BomLineId.of(LINE))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeLine(LINE))
                .isInstanceOf(BomLineNotFoundException.class);
            verify(boms, never()).save(any());
        }

        @Test void rejects_when_header_is_active() {
            Bom bom = activeBom();
            when(boms.findBomIdByLineId(BomLineId.of(LINE))).thenReturn(Optional.of(BomId.of(HEADER)));
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            assertThatThrownBy(() -> service.removeLine(LINE))
                .isInstanceOf(BomNotEditableException.class);
            verify(boms, never()).save(any());
        }

        @Test void rejects_when_line_is_not_actually_on_bom() {
            Bom bom = draftBom();  // empty — line LINE not present
            when(boms.findBomIdByLineId(BomLineId.of(LINE))).thenReturn(Optional.of(BomId.of(HEADER)));
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            assertThatThrownBy(() -> service.removeLine(LINE))
                .isInstanceOf(BomLineNotFoundException.class);
            verify(boms, never()).save(any());
        }
    }

    @Nested
    class Activate {

        @Test void activates_runs_cycle_check_per_component_and_kicks_rollup() {
            BomLine first = new BomLine(
                BomLineId.newId(), 1, COMPONENT, "RM-A", "Raw A", "raw_material",
                new BigDecimal("2"), BigDecimal.ZERO
            );
            BomLine second = new BomLine(
                BomLineId.newId(), 2, OTHER_COMPONENT, "RM-B", "Raw B", "raw_material",
                new BigDecimal("1"), BigDecimal.ZERO
            );
            Bom bom = Bom.reconstitute(
                BomId.of(HEADER), FINISHED, "FG-001", "Finished Good 1", "1",
                Bom.Status.DRAFT, List.of(first, second), 2L
            );
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));
            when(cycleDetector.wouldCreateCycle(any(), eq(FINISHED), eq(null))).thenReturn(false);

            service.activate(HEADER);

            assertThat(bom.status()).isEqualTo(Bom.Status.ACTIVE);
            verify(boms).save(bom);
            verify(cycleDetector).wouldCreateCycle(COMPONENT, FINISHED, null);
            verify(cycleDetector).wouldCreateCycle(OTHER_COMPONENT, FINISHED, null);
            verify(rollup).recomputeViaBom(FINISHED, "bom_activated");
        }

        @Test void no_op_when_already_active() {
            Bom bom = activeBom();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            service.activate(HEADER);

            verify(boms, never()).save(any());
            verifyNoInteractions(rollup);
        }

        @Test void rejects_when_header_does_not_exist() {
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomNotFoundException.class);
            verify(boms, never()).save(any());
        }

        @Test void rejects_from_inactive_status() {
            Bom bom = Bom.reconstitute(
                BomId.of(HEADER), FINISHED, "FG-001", "Finished Good 1", "1",
                Bom.Status.INACTIVE, List.of(), 5L
            );
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomNotEditableException.class)
                .hasMessageContaining("INACTIVE");
            verify(boms, never()).save(any());
        }

        @Test void rejects_empty_draft_via_aggregate_guard() {
            Bom bom = draftBom();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomNotEditableException.class)
                .hasMessageContaining("no lines");
            verify(boms, never()).save(any());
            verifyNoInteractions(rollup);
        }

        @Test void rejects_on_post_save_cycle_detection_and_skips_rollup() {
            Bom bom = draftBomWithOneLine();
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, null)).thenReturn(true);

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomCycleException.class)
                .hasMessageContaining("close a cycle");
            verify(boms).save(bom);
            verifyNoInteractions(rollup);
        }

        @Test void cycle_check_short_circuits_on_first_match() {
            Bom bom = Bom.reconstitute(
                BomId.of(HEADER), FINISHED, "FG-001", "Finished Good 1", "1",
                Bom.Status.DRAFT, List.of(
                    new BomLine(BomLineId.newId(), 1, COMPONENT, "RM-A", "Raw A", "raw_material",
                        BigDecimal.ONE, BigDecimal.ZERO),
                    new BomLine(BomLineId.newId(), 2, OTHER_COMPONENT, "RM-B", "Raw B", "raw_material",
                        BigDecimal.ONE, BigDecimal.ZERO)
                ), 2L
            );
            when(boms.findById(BomId.of(HEADER))).thenReturn(Optional.of(bom));
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, null)).thenReturn(true);

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomCycleException.class);
            verify(cycleDetector, times(1)).wouldCreateCycle(any(), any(), any());
        }
    }
}
