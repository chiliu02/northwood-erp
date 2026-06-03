package com.northwood.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.purchasing.domain.events.SupplierDetailsChanged;
import com.northwood.purchasing.domain.events.SupplierRegistered;
import com.northwood.purchasing.domain.events.SupplierStatusChanged;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierTest {

    @Test void register_lands_active_and_emits_registered() {
        Supplier s = Supplier.register("SUP-007", "Floorworks", "a@b.example", "+61", "Sydney");

        assertThat(s.status()).isEqualTo(Supplier.Status.ACTIVE);
        assertThat(s.isActive()).isTrue();
        assertThat(s.version()).isZero();
        List<DomainEvent> events = s.pullPendingEvents();
        assertThat(events).hasSize(1).first().isInstanceOf(SupplierRegistered.class);
        SupplierRegistered e = (SupplierRegistered) events.get(0);
        assertThat(e.supplierCode()).isEqualTo("SUP-007");
        assertThat(e.status()).isEqualTo("active");
    }

    @Test void change_status_emits_and_flips() {
        Supplier s = Supplier.register("SUP-007", "Floorworks", null, null, null);
        s.pullPendingEvents();

        s.changeStatus(Supplier.Status.BLOCKED, "fraud check");

        assertThat(s.status()).isEqualTo(Supplier.Status.BLOCKED);
        assertThat(s.isActive()).isFalse();
        List<DomainEvent> events = s.pullPendingEvents();
        assertThat(events).hasSize(1).first().isInstanceOf(SupplierStatusChanged.class);
        SupplierStatusChanged e = (SupplierStatusChanged) events.get(0);
        assertThat(e.oldStatus()).isEqualTo("active");
        assertThat(e.newStatus()).isEqualTo("blocked");
        assertThat(e.reason()).isEqualTo("fraud check");
    }

    @Test void change_status_to_same_is_a_no_op() {
        Supplier s = Supplier.register("SUP-007", "Floorworks", null, null, null);
        s.pullPendingEvents();

        s.changeStatus(Supplier.Status.ACTIVE, "noop");

        assertThat(s.pullPendingEvents()).isEmpty();
    }

    @Test void update_details_emits_and_suppresses_noop() {
        Supplier s = Supplier.register("SUP-007", "Floorworks", "a@b.example", "+61", "Sydney");
        s.pullPendingEvents();

        s.updateDetails("Floorworks Pty", "new@b.example", "+61", "Melbourne");
        List<DomainEvent> events = s.pullPendingEvents();
        assertThat(events).hasSize(1).first().isInstanceOf(SupplierDetailsChanged.class);

        // Re-applying identical values is a no-op.
        s.updateDetails("Floorworks Pty", "new@b.example", "+61", "Melbourne");
        assertThat(s.pullPendingEvents()).isEmpty();
    }
}
