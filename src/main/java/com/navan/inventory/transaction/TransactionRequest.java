package com.navan.inventory.transaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.navan.inventory.common.FeeLineDto;
import com.navan.inventory.common.TaxLineDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record TransactionRequest(
        @JsonProperty("external_id") @NotBlank String externalId,
        @NotNull String type,
        @NotNull String currency,
        @JsonProperty("occurred_at") @NotNull OffsetDateTime occurredAt,
        @NotNull BigDecimal total,
        @JsonProperty("tax_lines") @NotNull List<TaxLineDto> taxLines,
        @JsonProperty("fee_lines") @NotNull List<FeeLineDto> feeLines,
        @NotNull JsonNode metadata
) {}
