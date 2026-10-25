package com.navan.inventory.common;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NavanFeeKind {
    TRIP_FEE("trip_fee"),
    AGENT_CALL_FEE("agent_call_fee");

    private final String wire;

    NavanFeeKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public static NavanFeeKind fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (NavanFeeKind kind : values()) {
            if (kind.wire.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown navan_fee.fee_kind: " + value);
    }
}
