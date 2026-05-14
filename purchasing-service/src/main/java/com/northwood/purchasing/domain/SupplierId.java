package com.northwood.purchasing.domain;

import java.util.UUID;

public record SupplierId(UUID value) {

    public static SupplierId of(UUID value) {
        return new SupplierId(value);
    }
}
