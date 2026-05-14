package com.northwood.sales.domain;

import java.util.List;
import java.util.Optional;

/**
 * Repository port for the {@link Customer} aggregate. JDBC adapter at
 * {@code infrastructure/persistence/JdbcCustomerRepository}.
 */
public interface CustomerRepository {

    Optional<Customer> findById(CustomerId id);

    Optional<Customer> findByCode(String customerCode);

    List<Customer> findAll();

    void save(Customer customer);
}
