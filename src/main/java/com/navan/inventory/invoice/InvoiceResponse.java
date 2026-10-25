package com.navan.inventory.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.navan.inventory.common.FeeLineDto;
import com.navan.inventory.common.TaxLineDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record InvoiceResponse(
        String id,
        String currency,
        @JsonProperty("grand_total") BigDecimal grandTotal,
        @JsonProperty("transaction_ids") List<String> transactionIds,
        @JsonProperty("line_count") int lineCount,
        List<InvoiceLineResponse> lines,
        @JsonProperty("tax_rollup") List<TaxRollup> taxRollup,
        @JsonProperty("fee_rollup") List<FeeRollup> feeRollup,
        Map<String, BigDecimal> totals,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {}

record InvoiceLineResponse(
        String id,
        @JsonProperty("transaction_id") String transactionId,
        String type,
        BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLineDto> taxLines,
        @JsonProperty("fee_lines") List<FeeLineDto> feeLines,
        JsonNode metadata
) {}

record TaxRollup(
        String name,
        BigDecimal rate,
        BigDecimal amount
) {}

record FeeRollup(
        String name,
        BigDecimal amount
) {}
