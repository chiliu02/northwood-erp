package com.northwood.shared.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkuTest {

    @Test void rejects_null() {
        assertThrows(NullPointerException.class, () -> new Sku(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FG-TABLE-001",        // seed pattern
        "RM-BOARD-001",
        "RM-DRAWER-FRONT-001",
        "ABC",
        "A1",                  // 2-char minimum (pattern allows {1,49} after the first char)
        "WIDGET_42"
    })
    void accepts_valid_skus(String sku) {
        assertDoesNotThrow(() -> new Sku(sku));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                    // empty
        "A",                   // too short (pattern requires 2+ chars total)
        "fg-table-001",        // lowercase rejected
        "FG TABLE 001",        // spaces rejected
        "FG.TABLE.001",        // dots rejected
        "01",                  // first char must be alphanumeric
        "-FG-TABLE",           // first char must be alphanumeric
        "_FG-TABLE",           // first char must be alphanumeric
        "FG@TABLE"             // @ rejected
    })
    void rejects_invalid_skus(String sku) {
        assertThrows(IllegalArgumentException.class, () -> new Sku(sku));
    }

    @Test void rejects_skus_longer_than_50_chars() {
        String tooLong = "A".repeat(51);
        assertThrows(IllegalArgumentException.class, () -> new Sku(tooLong));
    }

    @Test void accepts_50_char_sku() {
        String maxLength = "A".repeat(50);
        assertDoesNotThrow(() -> new Sku(maxLength));
    }

    @Test void toString_returns_value() {
        assertEquals("FG-TABLE-001", new Sku("FG-TABLE-001").toString());
    }

    @Test void value_record_accessor() {
        assertEquals("RM-BOARD-001", new Sku("RM-BOARD-001").value());
    }

    @Test void equality_by_value() {
        assertEquals(new Sku("FG-TABLE-001"), new Sku("FG-TABLE-001"));
    }
}
