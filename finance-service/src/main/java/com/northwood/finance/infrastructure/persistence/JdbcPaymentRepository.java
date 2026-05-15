package com.northwood.finance.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.PaymentAllocation;
import com.northwood.finance.domain.PaymentId;
import com.northwood.finance.domain.PaymentRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private static final RowMapper<Payment> HEADER_MAPPER = (rs, n) -> {
        Date paymentDate = rs.getDate("payment_date");
        return Payment.reconstitute(
            PaymentId.of(rs.getObject("payment_id", UUID.class)),
            rs.getString("payment_number"),
            rs.getString("payment_direction"),
            rs.getString("payment_type"),
            rs.getObject("customer_id", UUID.class),
            rs.getObject("supplier_id", UUID.class),
            rs.getString("party_name"),
            paymentDate == null ? LocalDate.now() : paymentDate.toLocalDate(),
            rs.getString("payment_method"),
            rs.getString("currency_code"),
            rs.getBigDecimal("amount"),
            rs.getString("status"),
            List.of(),
            rs.getLong("version")
        );
    };

    private static final RowMapper<PaymentAllocation> ALLOCATION_MAPPER = (rs, n) -> new PaymentAllocation(
        rs.getObject("allocation_id", UUID.class),
        rs.getObject("customer_invoice_header_id", UUID.class),
        rs.getObject("supplier_invoice_header_id", UUID.class),
        rs.getBigDecimal("allocated_amount"),
        rs.getString("status")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcPaymentRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        List<Payment> matches = jdbc.query("""
            SELECT payment_id, payment_number, payment_direction, payment_type,
                   customer_id, supplier_id, party_name,
                   payment_date, payment_method, currency_code, amount, status, version
            FROM finance.payment
            WHERE payment_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        Payment stub = matches.get(0);
        List<PaymentAllocation> allocations = jdbc.query("""
            SELECT allocation_id, customer_invoice_header_id, supplier_invoice_header_id,
                   allocated_amount, status
            FROM finance.payment_allocation
            WHERE payment_id = ?
            ORDER BY allocated_at
            """, ALLOCATION_MAPPER, id.value());
        return Optional.of(Payment.reconstitute(
            stub.id(), stub.paymentNumber(), stub.paymentDirection(), stub.paymentType(),
            stub.customerId(), stub.supplierId(), stub.partyName(),
            stub.paymentDate(), stub.paymentMethod(), stub.currencyCode(),
            stub.amount(), stub.status(),
            allocations, stub.version()
        ));
    }

    @Override
    public List<Payment> findAll() {
        // Header-only — list views don't render allocation detail. Drilling
        // into a single payment triggers findById which loads allocations.
        return jdbc.query("""
            SELECT payment_id, payment_number, payment_direction, payment_type,
                   customer_id, supplier_id, party_name,
                   payment_date, payment_method, currency_code, amount, status, version
            FROM finance.payment
            ORDER BY posted_at DESC NULLS LAST, payment_id DESC
            """, HEADER_MAPPER);
    }

    @Override
    public void save(Payment p) {
        String actor = currentUser.currentUsername().orElse(null);
        if (p.version() == 0L) {
            insert(p, actor);
        } else {
            throw new IllegalStateException("Payment update path not supported in phase 5a");
        }
        for (DomainEvent event : p.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(Payment p, String actor) {
        Timestamp postedAt = Payment.POSTED.equals(p.status()) ? Timestamp.from(Instant.now()) : null;
        jdbc.update("""
            INSERT INTO finance.payment (
                payment_id, payment_number, payment_direction, payment_type,
                customer_id, supplier_id, party_name,
                payment_date, payment_method, currency_code,
                amount, status, version, posted_at,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            p.id().value(), p.paymentNumber(), p.paymentDirection(), p.paymentType(),
            p.customerId(), p.supplierId(), p.partyName(),
            Date.valueOf(p.paymentDate()),
            p.paymentMethod(), p.currencyCode(),
            p.amount(), p.status(),
            1L, postedAt,
            actor, actor
        );
        for (PaymentAllocation a : p.allocations()) {
            jdbc.update("""
                INSERT INTO finance.payment_allocation (
                    allocation_id, payment_id,
                    customer_invoice_header_id, supplier_invoice_header_id,
                    allocated_amount, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                a.id(), p.id().value(),
                a.customerInvoiceHeaderId(), a.supplierInvoiceHeaderId(),
                a.allocatedAmount(), a.status()
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO finance.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                Payment.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }

    @SuppressWarnings("unused")
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
