package com.northwood.inventory.domain;

import java.util.UUID;

public record GoodsReceiptId(UUID value) {

    public static GoodsReceiptId newId() {
        return new GoodsReceiptId(UUID.randomUUID());
    }

    public static GoodsReceiptId of(UUID value) {
        return new GoodsReceiptId(value);
    }
}
