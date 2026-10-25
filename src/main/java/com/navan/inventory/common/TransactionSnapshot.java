package com.navan.inventory.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * Frozen view of a live transaction used when copying invoice lines.
 */
public record TransactionSnapshot(
        String id,
        TransactionType type,
        String currency,
        BigDecimal total,
        List<TaxLineDto> taxLines,
        List<FeeLineDto> feeLines,
        JsonNode metadata
) {}
