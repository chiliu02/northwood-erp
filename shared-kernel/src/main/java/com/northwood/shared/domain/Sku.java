package com.northwood.shared.domain;

import java.util.regex.Pattern;

/**
 * Stock-keeping unit. Opaque outside product master, but format-validated
 * to catch typos early. Pattern matches the schema's seed data examples
 * (e.g. FG-TABLE-001, RM-BOARD-001).
 */
public record Sku(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z][A-Z0-9_-]{1,49}$");

    public Sku {
        Assert.notNull(value, "value");
        Assert.argument(PATTERN.matcher(value).matches(), "Invalid SKU: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
