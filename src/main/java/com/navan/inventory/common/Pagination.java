package com.navan.inventory.common;

import com.navan.inventory.common.error.ApiException;

public record Pagination(int limit, int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public static Pagination from(Integer limit, Integer offset) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        int resolvedOffset = offset == null ? 0 : offset;
        if (resolvedLimit < 1) {
            throw ApiException.badRequest("limit must be at least 1");
        }
        if (resolvedLimit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be at most " + MAX_LIMIT);
        }
        if (resolvedOffset < 0) {
            throw ApiException.badRequest("offset must be at least 0");
        }
        return new Pagination(resolvedLimit, resolvedOffset);
    }
}
