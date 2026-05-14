package com.northwood.finance.domain;

import java.util.UUID;

public record PaymentId(UUID value) {

    public static PaymentId newId() {
        return new PaymentId(UUID.randomUUID());
    }

    public static PaymentId of(UUID value) {
        return new PaymentId(value);
    }
}
