package com.navan.inventory.common;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TransactionType {
    FLIGHT("flight"),
    HOTEL("hotel"),
    RAIL("rail"),
    NAVAN_FEE("navan_fee");

    private final String wire;

    TransactionType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public static TransactionType fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (TransactionType type : values()) {
            if (type.wire.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown transaction type: " + value);
    }
}
