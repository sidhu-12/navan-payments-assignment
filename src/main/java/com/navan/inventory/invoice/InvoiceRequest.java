package com.navan.inventory.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record InvoiceRequest(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("transaction_ids") List<String> transactionIds
) {
    public List<String> normalizedIds() {
        if (transactionId != null && !transactionId.isBlank()) {
            return List.of(transactionId);
        }
        if (transactionIds != null && !transactionIds.isEmpty()) {
            return transactionIds;
        }
        return List.of();
    }
}
