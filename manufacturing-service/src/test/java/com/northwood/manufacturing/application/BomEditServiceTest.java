package com.northwood.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.northwood.manufacturing.domain.BomCycleDetector;
import com.northwood.manufacturing.domain.BomEditRepository;
import com.northwood.manufacturing.domain.BomEditRepository.HeaderRow;
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

    @Mock BomEditRepository edits;
    @Mock BomCycleDetector cycleDetector;
    @Mock MaterialsCostRollupService rollup;
    @Mock DiscontinuedProductLookup discontinuedProducts;

    private BomEditService service;

    @BeforeEach
    void setUp() {
        service = new BomEditService(edits, cycleDetector, rollup, discontinuedProducts);
    }

    private HeaderRow draftHeader() {
        return new HeaderRow(HEADER, FINISHED, "FG-001", "draft");
    }

    private HeaderRow activeHeader() {
        return new HeaderRow(HEADER, FINISHED, "FG-001", "active");
    }

    private HeaderRow inactiveHeader() {
        return new HeaderRow(HEADER, FINISHED, "FG-001", "inactive");
    }

    private CreateBomDraftCommand createDraft(String version) {
        return new CreateBomDraftCommand(FINISHED, "FG-001", "Finished Good 001", version);
    }

    private AddLineCommand addLine(UUID componentProductId) {
        return new AddLineCommand(
            componentProductId, "RM-001", "Raw Material 001", "raw_material",
            new BigDecimal("2.000"), new BigDecimal("0.05")
        );
    }

    @Nested
    class CreateDraft {

        @Test void inserts_header_with_provided_version() {
            UUID id = service.createDraft(createDraft("v2"));

            ArgumentCaptor<UUID> idCap = ArgumentCaptor.forClass(UUID.class);
            verify(edits).insertHeader(
                idCap.capture(), eq(FINISHED), eq("FG-001"), eq("Finished Good 001"), eq("v2")
            );
            assertThat(id).isEqualTo(idCap.getValue());
        }

        @Test void defaults_version_to_1_when_blank() {
            service.createDraft(createDraft(""));

            verify(edits).insertHeader(any(), any(), anyString(), anyString(), eq("1"));
        }

        @Test void defaults_version_to_1_when_null() {
            service.createDraft(createDraft(null));

            verify(edits).insertHeader(any(), any(), anyString(), anyString(), eq("1"));
        }

        @Test void rejects_null_finished_product_id() {
            CreateBomDraftCommand bad = new CreateBomDraftCommand(null, "FG-001", "x", "1");

            assertThatThrownBy(() -> service.createDraft(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedProductId");
            verifyNoInteractions(edits);
        }

        @Test void rejects_blank_sku() {
            CreateBomDraftCommand bad = new CreateBomDraftCommand(FINISHED, "  ", "x", "1");

            assertThatThrownBy(() -> service.createDraft(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sku");
            verifyNoInteractions(edits);
        }

        @Test void rejects_blank_name() {
            CreateBomDraftCommand bad = new CreateBomDraftCommand(FINISHED, "FG-001", "", "1");

            assertThatThrownBy(() -> service.createDraft(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
            verifyNoInteractions(edits);
        }
    }

    @Nested
    class AddLine {

        @Test void inserts_line_with_next_line_number() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.nextLineNumber(HEADER)).thenReturn(3);
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, HEADER)).thenReturn(false);

            UUID newLineId = service.addLine(HEADER, addLine(COMPONENT));

            verify(edits).insertLine(
                eq(newLineId), eq(HEADER), eq(3),
                eq(COMPONENT), eq("RM-001"), eq("Raw Material 001"), eq("raw_material"),
                eq(new BigDecimal("2.000")), eq(new BigDecimal("0.05"))
            );
        }

        @Test void rejects_when_header_does_not_exist() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addLine(HEADER, addLine(COMPONENT)))
                .isInstanceOf(BomNotFoundException.class);
            verify(edits, never()).insertLine(any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        }

        @Test void rejects_on_active_header() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(activeHeader()));

            assertThatThrownBy(() -> service.addLine(HEADER, addLine(COMPONENT)))
                .isInstanceOf(BomNotEditableException.class)
                .hasMessageContaining("active");
            verify(edits, never()).insertLine(any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        }

        @Test void rejects_when_component_equals_finished_product() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));

            assertThatThrownBy(() -> service.addLine(HEADER, addLine(FINISHED)))
                .isInstanceOf(BomCycleException.class)
                .hasMessageContaining("Component cannot equal");
            verify(edits, never()).insertLine(any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
            verifyNoInteractions(cycleDetector);
        }

        @Test void rejects_on_cycle_detection() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.nextLineNumber(HEADER)).thenReturn(1);
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, HEADER)).thenReturn(true);

            assertThatThrownBy(() -> service.addLine(HEADER, addLine(COMPONENT)))
                .isInstanceOf(BomCycleException.class)
                .hasMessageContaining("close a cycle");
        }

        @Test void rejects_when_component_is_discontinued() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(discontinuedProducts.isDiscontinued(COMPONENT)).thenReturn(true);

            assertThatThrownBy(() -> service.addLine(HEADER, addLine(COMPONENT)))
                .isInstanceOf(BomComponentDiscontinuedException.class)
                .hasMessageContaining("RM-001");
            verify(edits, never()).insertLine(any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
            verifyNoInteractions(cycleDetector);
        }
    }

    @Nested
    class RemoveLine {

        @Test void deletes_line_and_resolves_header() {
            when(edits.findHeaderIdByLineId(LINE)).thenReturn(Optional.of(HEADER));
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.deleteLine(LINE)).thenReturn(true);

            service.removeLine(LINE);

            verify(edits).deleteLine(LINE);
        }

        @Test void rejects_when_line_does_not_exist_at_lookup() {
            when(edits.findHeaderIdByLineId(LINE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeLine(LINE))
                .isInstanceOf(BomLineNotFoundException.class);
            verify(edits, never()).deleteLine(any());
        }

        @Test void rejects_when_header_is_active() {
            when(edits.findHeaderIdByLineId(LINE)).thenReturn(Optional.of(HEADER));
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(activeHeader()));

            assertThatThrownBy(() -> service.removeLine(LINE))
                .isInstanceOf(BomNotEditableException.class);
            verify(edits, never()).deleteLine(any());
        }

        @Test void rejects_when_delete_affects_no_rows() {
            when(edits.findHeaderIdByLineId(LINE)).thenReturn(Optional.of(HEADER));
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.deleteLine(LINE)).thenReturn(false);

            assertThatThrownBy(() -> service.removeLine(LINE))
                .isInstanceOf(BomLineNotFoundException.class);
        }
    }

    @Nested
    class Activate {

        @Test void marks_active_runs_cycle_check_per_component_and_kicks_rollup() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.countLines(HEADER)).thenReturn(2);
            when(edits.findComponentProductIds(HEADER)).thenReturn(List.of(COMPONENT, OTHER_COMPONENT));
            when(cycleDetector.wouldCreateCycle(any(), eq(FINISHED), eq(null))).thenReturn(false);

            service.activate(HEADER);

            verify(edits).markActive(HEADER);
            verify(cycleDetector).wouldCreateCycle(COMPONENT, FINISHED, null);
            verify(cycleDetector).wouldCreateCycle(OTHER_COMPONENT, FINISHED, null);
            verify(rollup).recomputeViaBom(FINISHED, "bom_activated");
        }

        @Test void no_op_when_already_active() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(activeHeader()));

            service.activate(HEADER);

            verify(edits, never()).markActive(any());
            verifyNoInteractions(rollup);
        }

        @Test void rejects_when_header_does_not_exist() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomNotFoundException.class);
            verify(edits, never()).markActive(any());
        }

        @Test void rejects_from_non_draft_non_active_status() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(inactiveHeader()));

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomNotEditableException.class)
                .hasMessageContaining("Cannot activate");
            verify(edits, never()).markActive(any());
        }

        @Test void rejects_empty_draft() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.countLines(HEADER)).thenReturn(0);

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomNotEditableException.class)
                .hasMessageContaining("no lines");
            verify(edits, never()).markActive(any());
            verifyNoInteractions(rollup);
        }

        @Test void rejects_on_cycle_after_marking_active() {
            // Cycle check runs after markActive; the @Transactional rollback is
            // what unwinds the markActive in production. The unit test verifies
            // the throw and that rollup is NOT triggered.
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.countLines(HEADER)).thenReturn(1);
            when(edits.findComponentProductIds(HEADER)).thenReturn(List.of(COMPONENT));
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, null)).thenReturn(true);

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomCycleException.class)
                .hasMessageContaining("close a cycle");
            verify(edits).markActive(HEADER);  // already called; rollback would unwind in production
            verifyNoInteractions(rollup);
        }

        @Test void cycle_check_short_circuits_on_first_match() {
            when(edits.findHeader(HEADER)).thenReturn(Optional.of(draftHeader()));
            when(edits.countLines(HEADER)).thenReturn(2);
            when(edits.findComponentProductIds(HEADER)).thenReturn(List.of(COMPONENT, OTHER_COMPONENT));
            when(cycleDetector.wouldCreateCycle(COMPONENT, FINISHED, null)).thenReturn(true);

            assertThatThrownBy(() -> service.activate(HEADER))
                .isInstanceOf(BomCycleException.class);
            verify(cycleDetector, times(1)).wouldCreateCycle(any(), any(), any());
        }
    }
}
