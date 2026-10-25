package com.navan.inventory.transaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.navan.inventory.common.FeeLineDto;
import com.navan.inventory.common.TaxLineDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record TransactionResponse(
        String id,
        @JsonProperty("external_id") String externalId,
        String type,
        String currency,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLineDto> taxLines,
        @JsonProperty("fee_lines") List<FeeLineDto> feeLines,
        JsonNode metadata,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {}
