package com.northwood.sales.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.application.CustomerService.DuplicateCustomerCodeException;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.CustomerId;
import com.northwood.sales.domain.CustomerRepository;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {

    private static final RowMapper<Customer> ROW_MAPPER = (rs, n) -> Customer.reconstitute(
        CustomerId.of(rs.getObject("customer_id", UUID.class)),
        rs.getString("customer_code"),
        rs.getString("name"),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("billing_address"),
        rs.getString("shipping_address"),
        Customer.Status.fromDb(rs.getString("status")),
        PaymentTerms.fromDb(rs.getString("default_payment_terms")),
        rs.getLong("version")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcCustomerRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Customer> findById(CustomerId id) {
        return jdbc.query(
            """
            SELECT customer_id, customer_code, name, email, phone,
                   billing_address, shipping_address, status, default_payment_terms, version
            FROM sales.customer WHERE customer_id = ?
            """,
            ROW_MAPPER, id.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<Customer> findByCode(String customerCode) {
        return jdbc.query(
            """
            SELECT customer_id, customer_code, name, email, phone,
                   billing_address, shipping_address, status, default_payment_terms, version
            FROM sales.customer WHERE customer_code = ?
            """,
            ROW_MAPPER, customerCode
        ).stream().findFirst();
    }

    @Override
    public List<Customer> findAll() {
        return jdbc.query(
            """
            SELECT customer_id, customer_code, name, email, phone,
                   billing_address, shipping_address, status, default_payment_terms, version
            FROM sales.customer
            ORDER BY customer_code
            """,
            ROW_MAPPER
        );
    }

    @Override
    public void save(Customer c) {
        String actor = currentUser.currentUsername().orElse(null);
        if (c.version() == 0) {
            insert(c, actor);
        } else {
            update(c, actor);
        }
        for (DomainEvent event : c.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(Customer c, String actor) {
        try {
            jdbc.update("""
                INSERT INTO sales.customer (
                    customer_id, customer_code, name,
                    email, phone, billing_address, shipping_address,
                    status, default_payment_terms, version,
                    created_by, last_modified_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                c.id().value(), c.customerCode(), c.name(),
                c.email(), c.phone(), c.billingAddress(), c.shippingAddress(),
                c.status().dbValue(), c.defaultPaymentTerms().dbValue(),
                1L,
                actor, actor
            );
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateCustomerCodeException(c.customerCode(), e);
        }
    }

    private void update(Customer c, String actor) {
        int rows = jdbc.update("""
            UPDATE sales.customer SET
                name = ?, email = ?, phone = ?,
                billing_address = ?, shipping_address = ?,
                status = ?, version = version + 1,
                last_modified_by = ?
            WHERE customer_id = ? AND version = ?
            """,
            c.name(), c.email(), c.phone(),
            c.billingAddress(), c.shippingAddress(),
            c.status().dbValue(),
            actor,
            c.id().value(), c.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "Customer " + c.id().value() + " was modified by another transaction"
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO sales.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                Customer.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventType(), e);
        }
    }

}
