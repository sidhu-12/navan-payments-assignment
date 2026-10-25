package com.navan.inventory.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TaxLineDto(
        @NotBlank String name,
        BigDecimal rate,
        @NotNull BigDecimal amount
) {}
