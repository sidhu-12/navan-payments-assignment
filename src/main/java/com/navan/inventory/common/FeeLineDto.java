package com.navan.inventory.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record FeeLineDto(
        @NotBlank String name,
        @NotNull BigDecimal amount
) {}
