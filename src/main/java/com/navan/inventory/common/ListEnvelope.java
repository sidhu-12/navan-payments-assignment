package com.navan.inventory.common;

import java.util.List;

public record ListEnvelope<T>(
        List<T> items,
        int limit,
        int offset,
        long total
) {}
