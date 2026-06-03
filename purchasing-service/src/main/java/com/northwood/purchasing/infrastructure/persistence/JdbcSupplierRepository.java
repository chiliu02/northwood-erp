package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.SupplierRepository;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSupplierRepository implements SupplierRepository {

    private static final RowMapper<Supplier> ROW_MAPPER = (rs, n) -> Supplier.reconstitute(
        SupplierId.of(rs.getObject("supplier_id", UUID.class)),
        rs.getString("supplier_code"),
        rs.getString("name"),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("address"),
        Supplier.Status.fromDb(rs.getString("status")),
        rs.getLong("version")
    );

    private static final String SELECT =
        "SELECT supplier_id, supplier_code, name, email, phone, address, status, version FROM purchasing.supplier";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcSupplierRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Supplier> findById(SupplierId id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                SELECT + " WHERE supplier_id = ?", ROW_MAPPER, id.value()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Supplier> findByCode(String supplierCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                SELECT + " WHERE supplier_code = ?", ROW_MAPPER, supplierCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean existsByCode(String supplierCode) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM purchasing.supplier WHERE supplier_code = ?", Integer.class, supplierCode);
        return count != null && count > 0;
    }

    @Override
    public List<Supplier> findAll() {
        return jdbc.query(SELECT + " ORDER BY supplier_code", ROW_MAPPER);
    }

    @Override
    public Supplier defaultSupplier() {
        try {
            return jdbc.queryForObject(
                SELECT + " WHERE status = 'active' ORDER BY supplier_code LIMIT 1", ROW_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                "No active supplier seeded; northwood_erp.sql provisions SUP-001 — check the install.");
        }
    }

    @Override
    public void save(Supplier supplier) {
        String actor = currentUser.currentUsername().orElse(null);
        if (supplier.version() == 0L) {
            // version = 1 on insert (not the table default 0) so the next edit
            // reconstitutes at version 1 and takes the UPDATE path — same
            // sentinel convention as JdbcCustomerRepository.
            jdbc.update("""
                INSERT INTO purchasing.supplier
                    (supplier_id, supplier_code, name, email, phone, address, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """,
                supplier.id().value(), supplier.supplierCode(), supplier.name(),
                supplier.email(), supplier.phone(), supplier.address(), supplier.status().dbValue()
            );
        } else {
            int updated = jdbc.update("""
                UPDATE purchasing.supplier
                   SET name = ?, email = ?, phone = ?, address = ?, status = ?, version = version + 1
                 WHERE supplier_id = ? AND version = ?
                """,
                supplier.name(), supplier.email(), supplier.phone(), supplier.address(),
                supplier.status().dbValue(), supplier.id().value(), supplier.version()
            );
            if (updated == 0) {
                throw new OptimisticLockingFailureException(
                    "Supplier " + supplier.id().value() + " was modified concurrently (expected version "
                        + supplier.version() + ")");
            }
        }
        for (DomainEvent event : supplier.pullPendingEvents()) {
            try {
                jdbc.update("""
                    INSERT INTO purchasing.outbox_message (
                        outbox_message_id, aggregate_type, aggregate_id,
                        event_type, event_version, payload, headers, status, actor_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                    """,
                    event.eventId(),
                    Supplier.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                    actor
                );
            } catch (JacksonException e) {
                throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
            }
        }
    }
}
