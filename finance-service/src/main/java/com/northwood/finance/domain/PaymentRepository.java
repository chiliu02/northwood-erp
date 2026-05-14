package com.northwood.finance.domain;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository {

    Optional<Payment> findById(PaymentId id);

    /** All payments (AP + AR), newest first. Used by the operational UI list view. */
    List<Payment> findAll();

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(Payment payment);
}
