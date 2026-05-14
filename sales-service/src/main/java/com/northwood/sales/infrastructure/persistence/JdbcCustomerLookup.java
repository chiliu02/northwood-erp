package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.CustomerLookup;
import com.northwood.sales.domain.Customer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerLookup implements CustomerLookup {

    private final JdbcTemplate jdbc;

    public JdbcCustomerLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerSummary> findByCode(String customerCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT customer_id, customer_code, name, status
                FROM sales.customer
                WHERE customer_code = ?
                """,
                (rs, n) -> new CustomerSummary(
                    rs.getObject("customer_id", UUID.class),
                    rs.getString("customer_code"),
                    rs.getString("name"),
                    Customer.Status.fromDb(rs.getString("status"))
                ),
                customerCode
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
