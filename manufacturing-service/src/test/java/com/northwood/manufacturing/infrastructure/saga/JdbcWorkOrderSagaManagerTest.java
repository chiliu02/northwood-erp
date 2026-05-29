package com.northwood.manufacturing.infrastructure.saga;

import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import com.northwood.manufacturing.application.saga.WorkOrderSagaPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the slim work-order saga manager. Asserts only saga-state
 * changes (state, current_step, saga.data). Side effects (event emission,
 * cross-aggregate reads) live with the worker shell / inbox handler shells
 * and are tested separately there.
 */
@ExtendWith(MockitoExtension.class)
class JdbcWorkOrderSagaManagerTest {

    private static final UUID SO_HEADER = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();
    private static final UUID WO = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock WorkOrderSagaPort sagas;
    @Mock PlatformTransactionManager txManager;

    private final ObjectMapper json = new ObjectMapper();
    private JdbcWorkOrderSagaManager manager;

    @BeforeEach
    void setUp() {
        manager = new JdbcWorkOrderSagaManager(sagas, json, txManager, 30L, 15L);
    }

    private WorkOrderSaga sagaInState(String state, String dataJson) {
        Instant now = Instant.now();
        return new WorkOrderSaga(
            UUID.randomUUID(), SO_HEADER, SO_LINE, WO,
            state, "step", null, 0, now, null, null,
            0L, dataJson == null ? "{}" : dataJson, now, now, null
        );
    }

    @Nested
    class Lifecycle {
        @Test void insertAttachedToWorkOrder_inserts_at_work_order_created() {
            manager.insertAttachedToWorkOrder(SO_HEADER, SO_LINE, WO, "{}");

            ArgumentCaptor<WorkOrderSaga> cap = ArgumentCaptor.forClass(WorkOrderSaga.class);
            verify(sagas).insert(cap.capture());
            assertThat(cap.getValue().state()).isEqualTo(WORK_ORDER_CREATED);
            assertThat(cap.getValue().workOrderId()).isEqualTo(WO);
        }
    }

    @Nested
    class ApplyRawMaterialsReserved {
        @Test void reserved_status_advances_to_raw_materials_reserved() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIAL_RESERVATION_REQUESTED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.applyRawMaterialsReserved(WO, "reserved", Map.of());

            assertThat(state).isEqualTo(RAW_MATERIALS_RESERVED);
        }

        @Test void partial_status_stashes_shortage_and_advances_to_raw_material_shortage() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIAL_RESERVATION_REQUESTED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.applyRawMaterialsReserved(
                WO, "partially_reserved", Map.of(PRODUCT, new BigDecimal("2")));

            assertThat(state).isEqualTo(RAW_MATERIAL_SHORTAGE);
            assertThat(saga.dataJson())
                .contains("shortageByProductId")
                .contains(PRODUCT.toString())
                .contains("\"2\"");
        }

        @Test void out_of_state_saga_returns_unchanged() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIALS_RESERVED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.applyRawMaterialsReserved(WO, "reserved", Map.of());

            assertThat(state).isEqualTo(RAW_MATERIALS_RESERVED);  // unchanged
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class UnparkOrNarrowShortage {
        private WorkOrderSaga sagaWithStash(String shortageQty) {
            String dataJson = json.writeValueAsString(
                Map.of("shortageByProductId", Map.of(PRODUCT.toString(), shortageQty))
            );
            return sagaInState(RAW_MATERIAL_SHORTAGE, dataJson);
        }

        @Test void receipt_covers_shortage_unparks_to_work_order_created() {
            WorkOrderSaga saga = sagaWithStash("4");
            UUID sagaId = saga.sagaId();
            when(sagas.findBySagaId(sagaId)).thenReturn(Optional.of(saga));

            String state = manager.unparkOrNarrowShortage(sagaId,
                Map.of(PRODUCT, new BigDecimal("4")));

            assertThat(state).isEqualTo(WORK_ORDER_CREATED);
        }

        @Test void receipt_partially_covers_narrows_stash_stays_in_shortage() {
            WorkOrderSaga saga = sagaWithStash("10");
            UUID sagaId = saga.sagaId();
            when(sagas.findBySagaId(sagaId)).thenReturn(Optional.of(saga));

            String state = manager.unparkOrNarrowShortage(sagaId,
                Map.of(PRODUCT, new BigDecimal("3")));

            assertThat(state).isEqualTo(RAW_MATERIAL_SHORTAGE);
            assertThat(saga.dataJson()).contains("\"7\"");
        }

        @Test void legacy_saga_without_stash_unparks_unconditionally() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIAL_SHORTAGE, "{}");
            UUID sagaId = saga.sagaId();
            when(sagas.findBySagaId(sagaId)).thenReturn(Optional.of(saga));

            String state = manager.unparkOrNarrowShortage(sagaId,
                Map.of(PRODUCT, new BigDecimal("1")));

            assertThat(state).isEqualTo(WORK_ORDER_CREATED);
        }

        @Test void saga_not_in_shortage_state_returns_null() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIALS_RESERVED, "{}");
            UUID sagaId = saga.sagaId();
            when(sagas.findBySagaId(sagaId)).thenReturn(Optional.of(saga));

            String state = manager.unparkOrNarrowShortage(sagaId,
                Map.of(PRODUCT, new BigDecimal("1")));

            assertThat(state).isNull();
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyManufacturingCompleted {
        @Test void non_terminal_saga_advances_to_completed() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIALS_RESERVED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.applyManufacturingCompleted(WO);

            assertThat(state).isEqualTo(COMPLETED);
            verify(sagas).update(saga);
        }

        @Test void already_terminal_saga_left_alone() {
            WorkOrderSaga saga = sagaInState(COMPLETED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.applyManufacturingCompleted(WO);

            assertThat(state).isEqualTo(COMPLETED);
            verify(sagas, never()).update(any());
        }

        @Test void no_saga_returns_null() {
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.empty());

            String state = manager.applyManufacturingCompleted(WO);

            assertThat(state).isNull();
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class CancelForWorkOrder {
        @Test void non_terminal_saga_flips_to_compensated() {
            WorkOrderSaga saga = sagaInState(RAW_MATERIAL_RESERVATION_REQUESTED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.cancelForWorkOrder(WO);

            assertThat(state).isEqualTo(COMPENSATED);
            verify(sagas).update(saga);
        }

        @Test void already_terminal_saga_left_alone() {
            WorkOrderSaga saga = sagaInState(COMPLETED, "{}");
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.of(saga));

            String state = manager.cancelForWorkOrder(WO);

            assertThat(state).isEqualTo(COMPLETED);
            verify(sagas, never()).update(any());
        }

        @Test void no_saga_returns_null() {
            when(sagas.findByWorkOrderId(WO)).thenReturn(Optional.empty());

            String state = manager.cancelForWorkOrder(WO);

            assertThat(state).isNull();
        }
    }
}
